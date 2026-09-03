use std::collections::{BTreeMap, HashMap};
use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use anyhow::{Result, anyhow};
use tokio_util::sync::CancellationToken;
use tokio_util::task::TaskTracker;

use crate::causal::CausalCapacity;
use crate::clock::RunClock;
use crate::http2::PersistentHttp2Client;
use crate::payment_state::PaymentStates;
use crate::phase_tracker::{PhaseTracker, WarmupOutcomes};
use crate::planner::{Planner, RunIdentity};
use crate::recorder::EventSender;
use crate::replay::ReplaySelector;
use loadtool_contract::model::ExecutionPlan;

pub(crate) const ACTIVE_REQUEST_TIMEOUT: Duration = Duration::from_secs(5);

#[derive(Clone, Copy, Debug)]
pub(crate) struct ActiveWindow {
    pub(crate) generation_end: Instant,
    pub(crate) hard_deadline: Instant,
}

#[derive(Clone, Debug)]
pub(crate) struct Pair {
    payer: String,
    receiver: String,
}

impl Pair {
    pub(crate) fn new(payer: String, receiver: String) -> Self {
        Self { payer, receiver }
    }

    pub(crate) fn payer(&self) -> &str {
        &self.payer
    }

    pub(crate) fn receiver(&self) -> &str {
        &self.receiver
    }
}

pub(crate) struct Runtime {
    pub(crate) plan: Arc<ExecutionPlan>,
    pub(crate) planner: Arc<Planner>,
    pub(crate) identity: RunIdentity,
    pub(crate) clock: RunClock,
    pub(crate) pairs: BTreeMap<u32, Arc<Pair>>,
    pub(crate) http_clients: HashMap<String, Arc<PersistentHttp2Client>>,
    pub(crate) states: Arc<PaymentStates>,
    pub(crate) recorder: EventSender,
    pub(crate) tasks: TaskTracker,
    pub(crate) cancellation: CancellationToken,
    pub(crate) warmup_tracker: Arc<PhaseTracker>,
    pub(crate) warmup_outcomes: WarmupOutcomes,
    pub(crate) warmup_slots: u64,
    pub(crate) warmup_hard_deadline: Instant,
    pub(crate) active_window: OnceLock<ActiveWindow>,
    pub(crate) accepting_work: AtomicBool,
    pub(crate) causal_capacity: Arc<CausalCapacity>,
    pub(crate) pacs008_replay: Option<(ReplaySelector, Duration)>,
    pub(crate) pacs002_replay: Option<(ReplaySelector, Duration)>,
    pub(crate) failure: RunFailure,
}

impl Runtime {
    pub(crate) fn active_window_cell() -> OnceLock<ActiveWindow> {
        OnceLock::new()
    }

    pub(crate) fn set_active_window(&self, generation_end: Instant, hard_deadline: Instant) {
        self.active_window
            .set(ActiveWindow {
                generation_end,
                hard_deadline,
            })
            .expect("active window is initialized exactly once");
    }

    pub(crate) fn pair_for_sequence(&self, sequence: u64) -> Result<Arc<Pair>> {
        let payment = self.planner.payment(sequence)?;
        self.pairs
            .get(&payment.pair_number)
            .cloned()
            .ok_or_else(|| anyhow!("unknown pair {}", payment.pair_number))
    }

    pub(crate) fn tracker_for(&self, sequence: u64) -> Option<Arc<PhaseTracker>> {
        (sequence < self.warmup_slots).then(|| Arc::clone(&self.warmup_tracker))
    }

    pub(crate) fn hard_deadline_for(&self, sequence: u64) -> Instant {
        if sequence < self.warmup_slots {
            self.warmup_hard_deadline
        } else {
            self.active_window
                .get()
                .expect("active payment has an active window")
                .hard_deadline
        }
    }

    pub(crate) fn request_timeout_for(&self, sequence: u64) -> Duration {
        let bootstrap_slots = self.plan.load.warmup.bootstrap.offered_tx_rate
            * self.plan.load.warmup.bootstrap.duration.as_secs();
        if sequence < bootstrap_slots {
            self.plan.load.warmup.bootstrap.request_timeout
        } else if sequence < self.warmup_slots {
            self.plan.load.warmup.steady.request_timeout
        } else {
            ACTIVE_REQUEST_TIMEOUT
        }
    }

    pub(crate) fn before_generation_end(&self, now: Instant) -> bool {
        self.active_window
            .get()
            .is_none_or(|window| now < window.generation_end)
    }

    pub(crate) fn check_operational(&self) -> Result<()> {
        if let Some(error) = self.failure.operational_error() {
            Err(anyhow!(error))
        } else {
            Ok(())
        }
    }
}

#[derive(Default)]
pub(crate) struct RunFailure {
    operational: Mutex<Option<String>>,
}

impl RunFailure {
    pub(crate) fn operational(&self, cancellation: &CancellationToken, error: impl ToString) {
        let mut current = self
            .operational
            .lock()
            .unwrap_or_else(|value| value.into_inner());
        if current.is_none() {
            *current = Some(error.to_string());
            cancellation.cancel();
        }
    }

    pub(crate) fn operational_error(&self) -> Option<String> {
        self.operational
            .lock()
            .unwrap_or_else(|value| value.into_inner())
            .clone()
    }
}

pub(crate) struct PhaseWork(Option<Arc<PhaseTracker>>);

impl PhaseWork {
    pub(crate) fn new(tracker: Option<Arc<PhaseTracker>>) -> Self {
        Self(tracker)
    }
}

impl Drop for PhaseWork {
    fn drop(&mut self) {
        if let Some(tracker) = &self.0
            && let Err(error) = tracker.done()
        {
            tracker.fail(error.to_string());
        }
    }
}
