use std::collections::{BTreeMap, HashMap};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result, anyhow, bail};
use bytes::Bytes;
use time::OffsetDateTime;
use time::format_description::well_known::Rfc3339;
use tokio_util::sync::CancellationToken;
use tokio_util::task::TaskTracker;

use crate::bundle::{Bundle, PreparedRun};
use crate::causal::{CausalCapacity, CausalKind, CausalPermit};
use crate::clock::RunClock;
use crate::event::{Event, MessageKind, NotificationKind, NotificationStatus, Participant};
use crate::generator_metrics::{
    DurationHistogram, FlowInFlight, GeneratorMetrics, HistogramSummary, InFlightMetrics,
    ProcessMetrics, PullMetrics, SlotMetrics, write_generator_metrics_atomic,
};
use crate::http2::{Http2Config, PersistentHttp2Client};
use crate::model::{ExecutionPlan, ReplayRule};
use crate::notification::NotificationPayload;
use crate::original::{AdmissionResult, submit_original};
use crate::pacer::{PacerMetrics, PhaseSchedule, spawn_pacer};
use crate::payload::{pacs002, pacs008};
use crate::payment_state::{OutcomeObservation, PaymentStates};
use crate::phase_tracker::PhaseTracker;
use crate::planner::{Planner, RunIdentity};
use crate::pull::{ProcessedNotification, PullClient, PullClientConfig, PullState};
use crate::recorder::{EventRecorder, EventSender};
use crate::replay::{ReplayDomain, ReplaySelector};
use crate::replay_task::{send_causal_admitted, send_replay};
use crate::run_window::{RunWindow, write_run_window_atomic};

const PACER_CHANNEL_CAPACITY: usize = 1;
const RECORDER_CAPACITY: usize = 65_536;
const CAUSAL_HTTP_CAPACITY: usize = 16_384;
const ACTIVE_REQUEST_TIMEOUT: Duration = Duration::from_secs(5);
const CONNECTION_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Clone, Debug, Default)]
pub struct SimulationOptions {
    pub central_transfer_ca_cert: Option<PathBuf>,
    pub central_transfer_client_cert_root: Option<PathBuf>,
    pub central_transfer_server_name: Option<String>,
    pub gateway_ca_cert: Option<PathBuf>,
    pub gateway_client_cert_root: Option<PathBuf>,
    pub gateway_server_name: Option<String>,
}

#[derive(Clone, Copy, Debug)]
struct ActiveWindow {
    start: Instant,
    generation_end: Instant,
    hard_deadline: Instant,
}

#[derive(Clone, Debug)]
struct Pair {
    payer: String,
    receiver: String,
}

#[derive(Clone, Copy, Debug)]
struct PlannedOriginal {
    sequence: u64,
    pair_number: u32,
    amount_cents: i64,
    pacs002_ordinal: Option<u64>,
    pacs008_replay: bool,
    pacs002_replay: bool,
    bucket_start: Instant,
    bucket_deadline: Instant,
    request_timeout: Duration,
    hard_deadline: Instant,
}

struct PullSession {
    ispb: String,
    receiver_role: bool,
    client: PullClient,
}

struct Runtime {
    plan: Arc<ExecutionPlan>,
    identity: RunIdentity,
    clock: RunClock,
    pairs: BTreeMap<u32, Arc<Pair>>,
    http_clients: HashMap<String, Arc<PersistentHttp2Client>>,
    states: Arc<PaymentStates>,
    recorder: EventSender,
    tasks: TaskTracker,
    cancellation: CancellationToken,
    warmup_tracker: Arc<PhaseTracker>,
    warmup_slots: u64,
    warmup_hard_deadline: Instant,
    active_window: RwLock<Option<ActiveWindow>>,
    accepting_work: AtomicBool,
    causal_capacity: Arc<CausalCapacity>,
    pacs008_replay: Option<(ReplaySelector, Duration)>,
    pacs002_replay: Option<(ReplaySelector, Duration)>,
    metrics: RuntimeMetrics,
    failure: RunFailure,
}

#[derive(Default)]
struct RuntimeMetrics {
    pacer: Mutex<Vec<PacerMetrics>>,
    dispatch: Mutex<Vec<HistogramSummary>>,
    original_started: AtomicU64,
    original_completed: AtomicU64,
    late_semantic_admissions: AtomicU64,
    causal_capacity_violations: AtomicU64,
    original_in_flight: FlowCounter,
    replay_in_flight: FlowCounter,
    pull_empty: AtomicU64,
    pull_batches: [AtomicU64; 16],
}

#[derive(Default)]
struct FlowCounter {
    current: AtomicU64,
    maximum: AtomicU64,
}

struct FlowGuard<'a>(&'a FlowCounter);

impl FlowCounter {
    fn enter(&self) -> FlowGuard<'_> {
        let current = self.current.fetch_add(1, Ordering::AcqRel) + 1;
        self.maximum.fetch_max(current, Ordering::Relaxed);
        FlowGuard(self)
    }

    fn snapshot(&self) -> FlowInFlight {
        FlowInFlight {
            current: self.current.load(Ordering::Acquire),
            maximum: self.maximum.load(Ordering::Acquire),
        }
    }
}

impl Drop for FlowGuard<'_> {
    fn drop(&mut self) {
        self.0.current.fetch_sub(1, Ordering::AcqRel);
    }
}

#[derive(Default)]
struct RunFailure {
    operational: Mutex<Option<String>>,
    generator: Mutex<Vec<String>>,
}

impl RunFailure {
    fn operational(&self, cancellation: &CancellationToken, error: impl ToString) {
        let mut current = self
            .operational
            .lock()
            .unwrap_or_else(|value| value.into_inner());
        if current.is_none() {
            *current = Some(error.to_string());
            cancellation.cancel();
        }
    }

    fn generator(&self, error: impl Into<String>) {
        self.generator
            .lock()
            .unwrap_or_else(|value| value.into_inner())
            .push(error.into());
    }

    fn operational_error(&self) -> Option<String> {
        self.operational
            .lock()
            .unwrap_or_else(|value| value.into_inner())
            .clone()
    }

    fn generator_violations(&self) -> Vec<String> {
        self.generator
            .lock()
            .unwrap_or_else(|value| value.into_inner())
            .clone()
    }
}

struct PhaseWork(Option<Arc<PhaseTracker>>);

impl Drop for PhaseWork {
    fn drop(&mut self) {
        if let Some(tracker) = &self.0
            && let Err(error) = tracker.done()
        {
            tracker.fail(error.to_string());
        }
    }
}

pub async fn run(bundle: Bundle, options: SimulationOptions) -> Result<()> {
    let PreparedRun { profile, plan } = bundle.load_prepared()?;
    bundle.prepare_outputs()?;
    let plan = Arc::new(plan);
    let pairs = build_pairs(&plan)?;
    let participant_ispbs = participant_ispbs(&pairs);

    println!(
        "prewarming central transfer HTTP/2 clients: psps={}",
        participant_ispbs.len()
    );
    let central = Http2Config::new(
        &profile.connections.central_transfer.base_url,
        options
            .central_transfer_ca_cert
            .unwrap_or_else(|| PathBuf::from(&profile.connections.central_transfer.ca_cert)),
        options
            .central_transfer_client_cert_root
            .unwrap_or_else(|| {
                PathBuf::from(&profile.connections.central_transfer.client_cert_root)
            }),
        options
            .central_transfer_server_name
            .unwrap_or_else(|| profile.connections.central_transfer.server_name.clone()),
    );
    let mut http_clients = HashMap::with_capacity(participant_ispbs.len());
    for ispb in &participant_ispbs {
        let client = central.connect(ispb).await?;
        client.prewarm(Instant::now() + CONNECTION_TIMEOUT).await?;
        http_clients.insert(ispb.clone(), Arc::new(client));
    }
    println!("central transfer HTTP/2 prewarm finished");

    println!(
        "prewarming notification Pull clients: psps={}",
        participant_ispbs.len()
    );
    let gateway = PullClientConfig::new(
        &profile.connections.notification_gateway.address,
        options
            .gateway_ca_cert
            .unwrap_or_else(|| PathBuf::from(&profile.connections.notification_gateway.ca_cert)),
        options.gateway_client_cert_root.unwrap_or_else(|| {
            PathBuf::from(&profile.connections.notification_gateway.client_cert_root)
        }),
        options
            .gateway_server_name
            .unwrap_or_else(|| profile.connections.notification_gateway.server_name.clone()),
    );
    let mut pull_sessions = Vec::with_capacity(participant_ispbs.len());
    for (ispb, receiver_role) in pull_specs(&pairs) {
        pull_sessions.push(PullSession {
            client: gateway.connect(&ispb).await?,
            ispb,
            receiver_role,
        });
    }
    println!("notification Pull clients ready");

    let monotonic_origin = Instant::now();
    let wall_origin = SystemTime::now();
    let clock = RunClock::new(monotonic_origin, wall_origin);
    let run_id = u64::try_from(
        wall_origin
            .duration_since(UNIX_EPOCH)
            .context("wall clock is before Unix epoch")?
            .as_nanos(),
    )
    .context("run timestamp overflows u64")?;
    let identity = RunIdentity::new(format!("rust-{run_id}"));
    let recorder = EventRecorder::start(
        bundle.events_dir(),
        Arc::clone(&plan),
        identity.clone(),
        clock,
        RECORDER_CAPACITY,
    )?;
    let recorder_sender = recorder.sender()?;

    let warmup_slots = warmup_slots(&plan)?;
    let warmup_planned_end = monotonic_origin
        .checked_add(plan.load.warmup.bootstrap.duration)
        .and_then(|value| value.checked_add(plan.load.warmup.steady.duration))
        .context("warmup deadline overflows Instant")?;
    let warmup_hard_deadline = warmup_planned_end
        .checked_add(plan.load.warmup.completion_timeout)
        .context("warmup hard deadline overflows Instant")?;
    let runtime = Arc::new(Runtime {
        states: Arc::new(PaymentStates::new(
            usize::try_from(plan.maximum_planned_slots()?)
                .context("planned payment state does not fit memory index")?,
        )),
        pacs008_replay: replay_selector(plan.replay.pacs008.as_ref(), ReplayDomain::Pacs008)?,
        pacs002_replay: replay_selector(plan.replay.pacs002.as_ref(), ReplayDomain::Pacs002)?,
        plan: Arc::clone(&plan),
        identity,
        clock,
        pairs,
        http_clients,
        recorder: recorder_sender,
        tasks: TaskTracker::new(),
        cancellation: CancellationToken::new(),
        warmup_tracker: Arc::new(PhaseTracker::new()),
        warmup_slots,
        warmup_hard_deadline,
        active_window: RwLock::new(None),
        accepting_work: AtomicBool::new(true),
        causal_capacity: Arc::new(CausalCapacity::new(CAUSAL_HTTP_CAPACITY)?),
        metrics: RuntimeMetrics::default(),
        failure: RunFailure::default(),
    });

    for session in pull_sessions {
        let runtime_for_pull = Arc::clone(&runtime);
        runtime.tasks.spawn(async move {
            pull_loop(runtime_for_pull, session).await;
        });
    }

    println!(
        "starting warmup: bootstrap_rate={}/s steady_rate={}/s",
        plan.load.warmup.bootstrap.offered_tx_rate, plan.load.warmup.steady.offered_tx_rate
    );
    run_generation_phase(
        Arc::clone(&runtime),
        monotonic_origin,
        plan.load.warmup.bootstrap.duration,
        plan.load.warmup.bootstrap.offered_tx_rate,
        0,
        plan.load.warmup.bootstrap.request_timeout,
        warmup_hard_deadline,
        true,
    )
    .await?;
    let bootstrap_slots =
        plan.load.warmup.bootstrap.offered_tx_rate * plan.load.warmup.bootstrap.duration.as_secs();
    run_generation_phase(
        Arc::clone(&runtime),
        monotonic_origin + plan.load.warmup.bootstrap.duration,
        plan.load.warmup.steady.duration,
        plan.load.warmup.steady.offered_tx_rate,
        bootstrap_slots,
        plan.load.warmup.steady.request_timeout,
        warmup_hard_deadline,
        true,
    )
    .await?;
    runtime.warmup_tracker.close_generation();
    println!("warmup generation finished; waiting for observable work");
    runtime
        .warmup_tracker
        .wait(warmup_hard_deadline)
        .await
        .context("warmup completion gate")?;
    check_operational(&runtime)?;

    let active_start = Instant::now();
    let generation_end = active_start
        .checked_add(plan.load.active_duration)
        .context("active generation deadline overflows Instant")?;
    let hard_deadline = generation_end
        .checked_add(plan.load.drain)
        .context("active hard deadline overflows Instant")?;
    *runtime
        .active_window
        .write()
        .unwrap_or_else(|value| value.into_inner()) = Some(ActiveWindow {
        start: active_start,
        generation_end,
        hard_deadline,
    });
    println!(
        "warmup completed; starting active load: offered_rate={}/s duration={:?}",
        plan.load.offered_tx_rate, plan.load.active_duration
    );
    run_generation_phase(
        Arc::clone(&runtime),
        active_start,
        plan.load.active_duration,
        plan.load.offered_tx_rate,
        warmup_slots,
        ACTIVE_REQUEST_TIMEOUT,
        hard_deadline,
        false,
    )
    .await?;

    println!("generation finished; observing fixed drain");
    tokio::select! {
        _ = tokio::time::sleep_until(hard_deadline.into()) => {}
        _ = runtime.cancellation.cancelled() => {}
    }
    runtime.accepting_work.store(false, Ordering::Release);
    runtime.cancellation.cancel();
    runtime.tasks.close();
    runtime.tasks.wait().await;

    let operational_error = runtime.failure.operational_error();
    let generator_violations = runtime.failure.generator_violations();
    let metrics_snapshot = snapshot_runtime_metrics(&runtime, &generator_violations);
    drop(runtime);
    let recorder_summary = recorder.close()?;

    if let Some(error) = operational_error {
        return Err(anyhow!(error));
    }
    write_run_window_atomic(
        bundle.run_window(),
        &RunWindow::new(
            &plan.profile,
            clock.unix_nanos(monotonic_origin)?,
            clock.unix_nanos(warmup_planned_end)?,
            clock.unix_nanos(active_start)?,
            clock.unix_nanos(generation_end)?,
            clock.unix_nanos(hard_deadline)?,
        ),
    )?;
    let mut metrics = metrics_snapshot;
    metrics.http_start_lateness = recorder_summary.http_start_lateness;
    metrics.http_duration = recorder_summary.http_duration;
    metrics.process = process_metrics();
    write_generator_metrics_atomic(bundle.generator_metrics(), &metrics)?;
    println!(
        "started={} completed={} missed={} generator_valid={} output={}",
        metrics.slots.started,
        metrics.slots.completed,
        metrics.slots.missed,
        metrics.valid,
        bundle.events_dir().display()
    );
    Ok(())
}

#[allow(clippy::too_many_arguments)]
async fn run_generation_phase(
    runtime: Arc<Runtime>,
    start: Instant,
    duration: Duration,
    rate: u64,
    first_sequence: u64,
    request_timeout: Duration,
    hard_deadline: Instant,
    warmup: bool,
) -> Result<()> {
    let schedule = PhaseSchedule::new(start, duration, rate, first_sequence)?;
    let (sender, mut receiver) = tokio::sync::mpsc::channel(PACER_CHANNEL_CAPACITY);
    let pacer = spawn_pacer(schedule, sender)?;
    let planner = Planner::new(&runtime.plan)?;
    let mut dispatch = DurationHistogram::new();

    while let Some(descriptor) = receiver.recv().await {
        dispatch.record_ns(nanos(
            Instant::now().saturating_duration_since(descriptor.bucket_start),
        ));
        for offset in 0..descriptor.request_count {
            let sequence = descriptor.first_sequence + offset;
            let payment = planner.payment(sequence)?;
            let pacs008_replay = runtime
                .pacs008_replay
                .as_ref()
                .is_some_and(|(selector, _)| selector.selected(sequence));
            let pacs002_replay = payment.pacs002_ordinal.is_some_and(|ordinal| {
                runtime
                    .pacs002_replay
                    .as_ref()
                    .is_some_and(|(selector, _)| selector.selected(ordinal))
            });
            if warmup {
                runtime.warmup_tracker.add()?;
            }
            let job = PlannedOriginal {
                sequence,
                pair_number: payment.pair_number,
                amount_cents: payment.amount_cents,
                pacs002_ordinal: payment.pacs002_ordinal,
                pacs008_replay,
                pacs002_replay,
                bucket_start: descriptor.bucket_start,
                bucket_deadline: descriptor.bucket_deadline,
                request_timeout,
                hard_deadline,
            };
            let runtime_for_task = Arc::clone(&runtime);
            runtime.tasks.spawn(async move {
                run_original(runtime_for_task, job, warmup).await;
            });
        }
    }
    let pacer_metrics = pacer
        .join()
        .map_err(|_| anyhow!("load-tool pacer thread panicked"))?;
    if pacer_metrics.missed_slots > 0 {
        runtime.failure.generator(format!(
            "pacer missed {} original slots in a generation phase",
            pacer_metrics.missed_slots
        ));
    }
    runtime
        .metrics
        .pacer
        .lock()
        .unwrap_or_else(|value| value.into_inner())
        .push(pacer_metrics);
    runtime
        .metrics
        .dispatch
        .lock()
        .unwrap_or_else(|value| value.into_inner())
        .push(dispatch.summary());
    check_operational(&runtime)
}

async fn run_original(runtime: Arc<Runtime>, job: PlannedOriginal, warmup: bool) {
    let tracker = warmup.then(|| Arc::clone(&runtime.warmup_tracker));
    let _root = PhaseWork(tracker.clone());
    let _in_flight = runtime.metrics.original_in_flight.enter();
    let pair = match runtime.pairs.get(&job.pair_number) {
        Some(pair) => Arc::clone(pair),
        None => {
            runtime.failure.operational(
                &runtime.cancellation,
                format!("unknown pair {}", job.pair_number),
            );
            return;
        }
    };
    let client = match runtime.http_clients.get(&pair.payer) {
        Some(client) => Arc::clone(client),
        None => {
            runtime.failure.operational(
                &runtime.cancellation,
                format!("missing HTTP/2 client for {}", pair.payer),
            );
            return;
        }
    };
    let end_to_end_id = runtime.identity.end_to_end_id(job.sequence);
    let created_at = rfc3339_now();
    let register_tracker = tracker.clone();
    let start_runtime = Arc::clone(&runtime);
    let start_tracker = tracker.clone();
    let result = submit_original(
        client.as_ref(),
        &runtime.states,
        job.sequence,
        job.bucket_deadline,
        job.request_timeout,
        job.hard_deadline,
        || {
            pacs008(
                &end_to_end_id,
                &pair.payer,
                &pair.receiver,
                job.amount_cents,
                &created_at,
            )
        },
        move |_, _| {
            if let Some(tracker) = &register_tracker {
                tracker.add()?;
                if job.pacs008_replay {
                    tracker.add()?;
                }
                if job.pacs002_ordinal.is_some() {
                    tracker.add()?;
                    if job.pacs002_replay {
                        tracker.add()?;
                    }
                }
            }
            Ok(())
        },
        move |_, body, started_at| {
            if job.pacs008_replay {
                let delay = start_runtime
                    .pacs008_replay
                    .as_ref()
                    .expect("selected replay has a rule")
                    .1;
                spawn_replay(
                    Arc::clone(&start_runtime),
                    job.sequence,
                    Participant::Payer,
                    MessageKind::Pacs008,
                    "/transfer",
                    body,
                    started_at,
                    job.hard_deadline,
                    delay,
                    false,
                    start_tracker.clone(),
                );
            }
            Ok(())
        },
    )
    .await;

    match result {
        Ok(AdmissionResult::Missed) => {
            runtime
                .metrics
                .late_semantic_admissions
                .fetch_add(1, Ordering::Relaxed);
            runtime.failure.generator(format!(
                "original sequence {} missed its bucket admission deadline",
                job.sequence
            ));
        }
        Ok(AdmissionResult::Completed(completion)) => {
            runtime
                .metrics
                .original_started
                .fetch_add(1, Ordering::Relaxed);
            runtime
                .metrics
                .original_completed
                .fetch_add(1, Ordering::Relaxed);
            if let Err(error) = runtime.recorder.record(Event::Pacs008Completed {
                sequence: job.sequence,
                created_offset_ns: offset_ns(runtime.clock, job.bucket_start),
                request_started_offset_ns: offset_ns(runtime.clock, completion.request_started_at),
                request_done_offset_ns: offset_ns(runtime.clock, completion.request_done_at),
                http_status: completion.attempt.status,
                replay_selected: job.pacs008_replay,
            }) {
                runtime.failure.operational(&runtime.cancellation, error);
            }
            if !(200..300).contains(&completion.attempt.status)
                && let Some(tracker) = tracker
            {
                tracker.fail(format!(
                    "warmup payment {} returned HTTP {}",
                    job.sequence, completion.attempt.status
                ));
            }
        }
        Err(error) => runtime.failure.operational(&runtime.cancellation, error),
    }
}

#[allow(clippy::too_many_arguments)]
fn spawn_replay(
    runtime: Arc<Runtime>,
    sequence: u64,
    sender: Participant,
    message: MessageKind,
    path: &'static str,
    body: Bytes,
    request_started_at: Instant,
    hard_deadline: Instant,
    delay: Duration,
    causal: bool,
    tracker: Option<Arc<PhaseTracker>>,
) {
    if !runtime.accepting_work.load(Ordering::Acquire) {
        runtime.failure.generator(format!(
            "{} replay for sequence {sequence} was created after semantic shutdown",
            message.as_str()
        ));
        return;
    }
    let client_ispb = match runtime.pair_for_sequence(sequence) {
        Ok(pair) => match sender {
            Participant::Payer => pair.payer.clone(),
            Participant::Receiver => pair.receiver.clone(),
        },
        Err(error) => {
            runtime.failure.operational(&runtime.cancellation, error);
            return;
        }
    };
    let client = match runtime.http_clients.get(&client_ispb) {
        Some(client) => Arc::clone(client),
        None => {
            runtime.failure.operational(
                &runtime.cancellation,
                format!("missing replay HTTP/2 client for {client_ispb}"),
            );
            return;
        }
    };
    let task_runtime = Arc::clone(&runtime);
    runtime.tasks.spawn(async move {
        let _work = PhaseWork(tracker.clone());
        let _in_flight = task_runtime.metrics.replay_in_flight.enter();
        let due_at = request_started_at
            .checked_add(delay)
            .unwrap_or(hard_deadline);
        tokio::time::sleep_until(due_at.into()).await;
        let started_at = Instant::now();
        let result = if causal {
            send_replay(
                client.as_ref(),
                Some(task_runtime.causal_capacity.as_ref()),
                started_at,
                path,
                body,
                hard_deadline,
            )
            .await
        } else {
            send_replay(client.as_ref(), None, started_at, path, body, hard_deadline).await
        };
        let done_at = Instant::now();
        match result {
            Ok(attempt) => {
                if let Err(error) = task_runtime.recorder.record(Event::ReplayCompleted {
                    sequence,
                    sender,
                    message,
                    request_started_offset_ns: offset_ns(task_runtime.clock, started_at),
                    request_done_offset_ns: offset_ns(task_runtime.clock, done_at),
                    http_status: attempt.status,
                }) {
                    task_runtime
                        .failure
                        .operational(&task_runtime.cancellation, error);
                }
                if !(200..300).contains(&attempt.status)
                    && let Some(tracker) = tracker
                {
                    tracker.fail(format!(
                        "warmup replay for sequence {sequence} returned HTTP {}",
                        attempt.status
                    ));
                }
            }
            Err(error) => {
                task_runtime.failure.generator(format!(
                    "{} replay for sequence {sequence} did not complete: {error}",
                    message.as_str()
                ));
                if let Some(tracker) = tracker {
                    tracker.fail(error.to_string());
                }
            }
        }
    });
}

async fn pull_loop(runtime: Arc<Runtime>, mut session: PullSession) {
    let mut state = PullState::new();
    loop {
        let result = tokio::select! {
            _ = runtime.cancellation.cancelled() => return,
            response = session.client.pull(state.cursor()) => response,
        };
        let batch = match result {
            Ok(batch) => batch,
            Err(error) if transient_pull_error(&error) => {
                tokio::select! {
                    _ = runtime.cancellation.cancelled() => return,
                    _ = tokio::time::sleep(Duration::from_millis(100)) => {}
                }
                continue;
            }
            Err(error) => {
                runtime.failure.operational(
                    &runtime.cancellation,
                    format!("notification Pull for {}: {error}", session.ispb),
                );
                return;
            }
        };
        observe_pull(&runtime, batch.notifications.len());
        let received_at = Instant::now();
        let mut capacity_violation = None;
        let result = state.process(batch, |notification| {
            process_pulled_notification(
                &runtime,
                &session.ispb,
                session.receiver_role,
                received_at,
                notification,
                &mut capacity_violation,
            )
        });
        if let Err(error) = result {
            if let Some(error) = capacity_violation {
                runtime
                    .metrics
                    .causal_capacity_violations
                    .fetch_add(1, Ordering::Relaxed);
                runtime.failure.generator(error);
            } else {
                runtime.failure.operational(
                    &runtime.cancellation,
                    format!("process notification Pull for {}: {error}", session.ispb),
                );
            }
            return;
        }
    }
}

fn process_pulled_notification(
    runtime: &Arc<Runtime>,
    session_ispb: &str,
    receiver_role: bool,
    received_at: Instant,
    notification: &ProcessedNotification,
    capacity_violation: &mut Option<String>,
) -> Result<()> {
    for payload in &notification.payloads {
        match payload {
            NotificationPayload::Pacs008 { end_to_end_id } => {
                let Some(sequence) = runtime.identity.sequence(end_to_end_id) else {
                    continue;
                };
                let pair = runtime.pair_for_sequence(sequence)?;
                let participant = if session_ispb == pair.receiver {
                    Participant::Receiver
                } else if session_ispb == pair.payer {
                    Participant::Payer
                } else {
                    continue;
                };
                runtime.recorder.record(Event::Notification {
                    sequence,
                    participant,
                    kind: NotificationKind::Pacs008Received,
                    received_offset_ns: offset_ns(runtime.clock, received_at),
                    status: NotificationStatus::None,
                    reason_codes: Vec::new(),
                })?;
                if receiver_role
                    && participant == Participant::Receiver
                    && runtime.states.claim_pacs002(sequence)
                    && let Err(error) = spawn_pacs002(Arc::clone(runtime), sequence)
                {
                    *capacity_violation = Some(error.to_string());
                    return Err(error);
                }
            }
            NotificationPayload::Pacs002 {
                end_to_end_id,
                status,
                reason_codes,
            } => {
                let Some(sequence) = runtime.identity.sequence(end_to_end_id) else {
                    continue;
                };
                let pair = runtime.pair_for_sequence(sequence)?;
                let participant = if session_ispb == pair.payer {
                    Participant::Payer
                } else if session_ispb == pair.receiver {
                    Participant::Receiver
                } else {
                    continue;
                };
                runtime.recorder.record(Event::Notification {
                    sequence,
                    participant,
                    kind: NotificationKind::Pacs002Received,
                    received_offset_ns: offset_ns(runtime.clock, received_at),
                    status: notification_status(status),
                    reason_codes: reason_codes.clone(),
                })?;
                let planner = Planner::new(&runtime.plan)?;
                let payment = planner.payment(sequence)?;
                let expectation = &runtime.plan.scenarios[payment.scenario_index]
                    .expectations
                    .payer_notification;
                let matches = participant == Participant::Payer
                    && status == &expectation.status
                    && same_reasons(reason_codes, &expectation.reason_codes);
                match runtime.states.observe_outcome(sequence, matches) {
                    OutcomeObservation::MatchedFirst => {
                        if let Some(tracker) = runtime.tracker_for(sequence) {
                            tracker.done()?;
                        }
                    }
                    OutcomeObservation::ContradictionFirst => {
                        if let Some(tracker) = runtime.tracker_for(sequence) {
                            tracker.fail(format!(
                                "warmup payment {sequence} received contradictory payer outcome"
                            ));
                        }
                    }
                    OutcomeObservation::IgnoredUncommitted
                    | OutcomeObservation::MatchedAgain
                    | OutcomeObservation::ContradictionAgain => {}
                }
            }
        }
    }
    Ok(())
}

fn spawn_pacs002(runtime: Arc<Runtime>, sequence: u64) -> Result<()> {
    let permit = runtime.causal_capacity.try_acquire(CausalKind::Original)?;
    let tracker = runtime.tracker_for(sequence);
    let task_runtime = Arc::clone(&runtime);
    runtime.tasks.spawn(async move {
        run_pacs002(task_runtime, sequence, permit, tracker).await;
    });
    Ok(())
}

async fn run_pacs002(
    runtime: Arc<Runtime>,
    sequence: u64,
    permit: CausalPermit,
    tracker: Option<Arc<PhaseTracker>>,
) {
    let _work = PhaseWork(tracker.clone());
    let pair = match runtime.pair_for_sequence(sequence) {
        Ok(pair) => pair,
        Err(error) => {
            runtime.failure.operational(&runtime.cancellation, error);
            return;
        }
    };
    let client = match runtime.http_clients.get(&pair.receiver) {
        Some(client) => Arc::clone(client),
        None => {
            runtime.failure.operational(
                &runtime.cancellation,
                format!("missing causal HTTP/2 client for {}", pair.receiver),
            );
            return;
        }
    };
    let started_at = Instant::now();
    let hard_deadline = runtime.hard_deadline_for(sequence);
    let request_deadline = http_deadline(
        started_at,
        runtime.request_timeout_for(sequence),
        hard_deadline,
    );
    let body = match pacs002(&runtime.identity.end_to_end_id(sequence), &rfc3339_now()) {
        Ok(body) => body,
        Err(error) => {
            runtime.failure.operational(&runtime.cancellation, error);
            return;
        }
    };
    let planner = match Planner::new(&runtime.plan).and_then(|value| value.payment(sequence)) {
        Ok(payment) => payment,
        Err(error) => {
            runtime.failure.operational(&runtime.cancellation, error);
            return;
        }
    };
    let replay_selected = planner.pacs002_ordinal.is_some_and(|ordinal| {
        runtime
            .pacs002_replay
            .as_ref()
            .is_some_and(|(selector, _)| {
                selector.selected(ordinal) && runtime.before_generation_end(started_at)
            })
    });
    if replay_selected {
        let delay = runtime
            .pacs002_replay
            .as_ref()
            .expect("selected PACS.002 replay has a rule")
            .1;
        spawn_replay(
            Arc::clone(&runtime),
            sequence,
            Participant::Receiver,
            MessageKind::Pacs002,
            "/transfer/status",
            body.clone(),
            started_at,
            hard_deadline,
            delay,
            true,
            tracker.clone(),
        );
    }
    let attempt = send_causal_admitted(
        client.as_ref(),
        permit,
        "/transfer/status",
        body,
        request_deadline,
    )
    .await;
    let done_at = Instant::now();
    match attempt {
        Ok(attempt) => {
            if let Err(error) = runtime.recorder.record(Event::Pacs002Completed {
                sequence,
                request_started_offset_ns: offset_ns(runtime.clock, started_at),
                request_done_offset_ns: offset_ns(runtime.clock, done_at),
                http_status: attempt.status,
                replay_selected,
            }) {
                runtime.failure.operational(&runtime.cancellation, error);
                return;
            }
            if (200..300).contains(&attempt.status) {
                if let Err(error) = runtime.recorder.record(Event::Notification {
                    sequence,
                    participant: Participant::Receiver,
                    kind: NotificationKind::Pacs002Sent,
                    received_offset_ns: offset_ns(runtime.clock, done_at),
                    status: NotificationStatus::None,
                    reason_codes: Vec::new(),
                }) {
                    runtime.failure.operational(&runtime.cancellation, error);
                }
            } else if let Some(tracker) = tracker {
                tracker.fail(format!(
                    "warmup PACS.002 for sequence {sequence} returned HTTP {}",
                    attempt.status
                ));
            }
        }
        Err(error) => runtime.failure.operational(&runtime.cancellation, error),
    }
}

impl Runtime {
    fn pair_for_sequence(&self, sequence: u64) -> Result<Arc<Pair>> {
        let planner = Planner::new(&self.plan)?;
        let payment = planner.payment(sequence)?;
        self.pairs
            .get(&payment.pair_number)
            .cloned()
            .ok_or_else(|| anyhow!("unknown pair {}", payment.pair_number))
    }

    fn tracker_for(&self, sequence: u64) -> Option<Arc<PhaseTracker>> {
        (sequence < self.warmup_slots).then(|| Arc::clone(&self.warmup_tracker))
    }

    fn hard_deadline_for(&self, sequence: u64) -> Instant {
        if sequence < self.warmup_slots {
            self.warmup_hard_deadline
        } else {
            self.active_window
                .read()
                .unwrap_or_else(|value| value.into_inner())
                .expect("active payment has an active window")
                .hard_deadline
        }
    }

    fn request_timeout_for(&self, sequence: u64) -> Duration {
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

    fn before_generation_end(&self, now: Instant) -> bool {
        self.active_window
            .read()
            .unwrap_or_else(|value| value.into_inner())
            .is_none_or(|window| now < window.generation_end)
    }
}

fn build_pairs(plan: &ExecutionPlan) -> Result<BTreeMap<u32, Arc<Pair>>> {
    let mut pairs = BTreeMap::new();
    for scenario in &plan.scenarios {
        let count = scenario
            .participants
            .hot_pair_count
            .checked_add(scenario.participants.cold_pair_count)
            .context("participant pair count overflows")?;
        for offset in 0..count {
            let number = scenario
                .participants
                .pair_number_start
                .checked_add(offset)
                .context("participant pair number overflows")?;
            pairs.entry(number).or_insert_with(|| {
                Arc::new(Pair {
                    payer: format!("10{number:06}"),
                    receiver: format!("20{number:06}"),
                })
            });
        }
    }
    if pairs.is_empty() {
        bail!("execution plan has no participant pairs");
    }
    Ok(pairs)
}

fn participant_ispbs(pairs: &BTreeMap<u32, Arc<Pair>>) -> Vec<String> {
    let mut values = Vec::with_capacity(pairs.len() * 2);
    for pair in pairs.values() {
        values.push(pair.payer.clone());
        values.push(pair.receiver.clone());
    }
    values.sort();
    values.dedup();
    values
}

fn pull_specs(pairs: &BTreeMap<u32, Arc<Pair>>) -> Vec<(String, bool)> {
    let mut specs = BTreeMap::new();
    for pair in pairs.values() {
        specs.insert(pair.payer.clone(), false);
        specs.insert(pair.receiver.clone(), true);
    }
    specs.into_iter().collect()
}

fn replay_selector(
    rule: Option<&ReplayRule>,
    domain: ReplayDomain,
) -> Result<Option<(ReplaySelector, Duration)>> {
    rule.map(|rule| {
        Ok((
            ReplaySelector::new(rule.share, domain)?,
            Duration::from_secs(rule.delay_seconds),
        ))
    })
    .transpose()
}

fn warmup_slots(plan: &ExecutionPlan) -> Result<u64> {
    let bootstrap = plan
        .load
        .warmup
        .bootstrap
        .offered_tx_rate
        .checked_mul(plan.load.warmup.bootstrap.duration.as_secs())
        .context("bootstrap slots overflow")?;
    let steady = plan
        .load
        .warmup
        .steady
        .offered_tx_rate
        .checked_mul(plan.load.warmup.steady.duration.as_secs())
        .context("steady slots overflow")?;
    bootstrap
        .checked_add(steady)
        .context("warmup slots overflow")
}

fn observe_pull(runtime: &Runtime, size: usize) {
    let now = Instant::now();
    let active = runtime
        .active_window
        .read()
        .unwrap_or_else(|value| value.into_inner());
    if !active.is_some_and(|window| now >= window.start && now < window.generation_end) {
        return;
    }
    if size == 0 {
        runtime.metrics.pull_empty.fetch_add(1, Ordering::Relaxed);
    } else if size < runtime.metrics.pull_batches.len() {
        runtime.metrics.pull_batches[size].fetch_add(1, Ordering::Relaxed);
    } else {
        runtime.failure.generator(format!(
            "notification Pull returned {size} messages above protocol maximum"
        ));
    }
}

fn snapshot_runtime_metrics(runtime: &Runtime, violations: &[String]) -> GeneratorMetrics {
    let pacers = runtime
        .metrics
        .pacer
        .lock()
        .unwrap_or_else(|value| value.into_inner());
    let planned = pacers.iter().map(|value| value.planned_slots).sum();
    let dispatched = pacers.iter().map(|value| value.dispatched_slots).sum();
    let pacer_missed: u64 = pacers.iter().map(|value| value.missed_slots).sum();
    let late = runtime
        .metrics
        .late_semantic_admissions
        .load(Ordering::Acquire);
    let mut pull_counts = [0u64; 16];
    for (index, value) in pull_counts.iter_mut().enumerate().skip(1) {
        *value = runtime.metrics.pull_batches[index].load(Ordering::Acquire);
    }
    GeneratorMetrics {
        valid: violations.is_empty() && pacer_missed == 0 && late == 0,
        violations: violations.to_vec(),
        slots: SlotMetrics {
            planned,
            dispatched,
            started: runtime.metrics.original_started.load(Ordering::Acquire),
            completed: runtime.metrics.original_completed.load(Ordering::Acquire),
            missed: pacer_missed + late,
        },
        pacer_lateness: combine_histograms(pacers.iter().map(|value| value.pacer_lateness)),
        dispatch_lateness: combine_histograms(
            runtime
                .metrics
                .dispatch
                .lock()
                .unwrap_or_else(|value| value.into_inner())
                .iter()
                .copied(),
        ),
        late_semantic_admissions: late,
        generator_capacity_violations: runtime
            .metrics
            .causal_capacity_violations
            .load(Ordering::Acquire),
        spin_wall_time_ns: pacers.iter().map(|value| value.spin_wall_time_ns).sum(),
        in_flight: InFlightMetrics {
            original: runtime.metrics.original_in_flight.snapshot(),
            pacs008_replay: runtime.metrics.replay_in_flight.snapshot(),
            causal_http: FlowInFlight {
                current: runtime.causal_capacity.current(),
                maximum: runtime.causal_capacity.maximum(),
            },
        },
        pull: PullMetrics {
            count: pull_counts.iter().sum(),
            empty_responses: runtime.metrics.pull_empty.load(Ordering::Acquire),
            batch_size_counts: pull_counts,
        },
        ..GeneratorMetrics::default()
    }
}

fn combine_histograms(values: impl Iterator<Item = HistogramSummary>) -> HistogramSummary {
    values.fold(HistogramSummary::default(), |mut combined, value| {
        combined.count += value.count;
        combined.p50_ns = combined.p50_ns.max(value.p50_ns);
        combined.p95_ns = combined.p95_ns.max(value.p95_ns);
        combined.p99_ns = combined.p99_ns.max(value.p99_ns);
        combined.max_ns = combined.max_ns.max(value.max_ns);
        combined
    })
}

fn process_metrics() -> ProcessMetrics {
    #[cfg(target_os = "linux")]
    unsafe {
        let mut usage = std::mem::zeroed::<libc::rusage>();
        if libc::getrusage(libc::RUSAGE_SELF, &mut usage) == 0 {
            return ProcessMetrics {
                user_cpu_ns: timeval_ns(usage.ru_utime),
                system_cpu_ns: timeval_ns(usage.ru_stime),
                maximum_rss_bytes: u64::try_from(usage.ru_maxrss)
                    .unwrap_or_default()
                    .saturating_mul(1024),
            };
        }
    }
    ProcessMetrics::default()
}

#[cfg(target_os = "linux")]
fn timeval_ns(value: libc::timeval) -> u64 {
    u64::try_from(value.tv_sec)
        .unwrap_or_default()
        .saturating_mul(1_000_000_000)
        .saturating_add(
            u64::try_from(value.tv_usec)
                .unwrap_or_default()
                .saturating_mul(1_000),
        )
}

fn transient_pull_error(error: &anyhow::Error) -> bool {
    error.downcast_ref::<tonic::Status>().is_some_and(|status| {
        matches!(
            status.code(),
            tonic::Code::Unavailable | tonic::Code::DeadlineExceeded
        )
    })
}

fn notification_status(status: &str) -> NotificationStatus {
    match status {
        "" => NotificationStatus::None,
        "ACSC" => NotificationStatus::Acsc,
        "RJCT" => NotificationStatus::Rjct,
        other => NotificationStatus::Other(other.to_owned()),
    }
}

fn same_reasons(left: &[String], right: &[String]) -> bool {
    let mut left = left.to_vec();
    let mut right = right.to_vec();
    left.sort();
    right.sort();
    left == right
}

fn check_operational(runtime: &Runtime) -> Result<()> {
    if let Some(error) = runtime.failure.operational_error() {
        Err(anyhow!(error))
    } else {
        Ok(())
    }
}

fn rfc3339_now() -> String {
    OffsetDateTime::now_utc()
        .format(&Rfc3339)
        .expect("UTC time is always RFC3339 representable")
}

fn offset_ns(clock: RunClock, instant: Instant) -> u64 {
    nanos(
        instant
            .checked_duration_since(clock.monotonic_origin())
            .unwrap_or_default(),
    )
}

fn nanos(duration: Duration) -> u64 {
    u64::try_from(duration.as_nanos()).unwrap_or(u64::MAX)
}

pub fn http_deadline(
    request_started_at: Instant,
    request_timeout: Duration,
    hard_deadline: Instant,
) -> Instant {
    request_started_at
        .checked_add(request_timeout)
        .unwrap_or(hard_deadline)
        .min(hard_deadline)
}
