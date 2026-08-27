use std::collections::{BTreeMap, HashMap};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result, anyhow, bail};
use bytes::Bytes;
use time::OffsetDateTime;
use time::format_description::well_known::Rfc3339;
use tokio_util::sync::CancellationToken;
use tokio_util::task::TaskTracker;

use crate::causal::{CausalCapacity, CausalKind, CausalPermit};
use crate::clock::RunClock;
use crate::http2::{Http2Config, PersistentHttp2Client, PersistentPreparedRequest};
use crate::notification::NotificationPayload;
use crate::original::{
    AdmissionOutcome, PreparedOriginal, StartedOriginal, admit_original, prepare_original,
};
use crate::pacer::{PREPARATION_LEAD, PhaseSchedule, PreparedBucket, spawn_prepared_pacer};
use crate::payload::{pacs002, pacs008};
use crate::payment_state::PaymentStates;
use crate::phase_tracker::{PhaseTracker, WarmupObservation, WarmupOutcomes};
use crate::planner::{Planner, RunIdentity};
use crate::pull::{ProcessedNotification, PullClient, PullClientConfig, PullState};
use crate::recorder::{EventRecorder, EventSender};
use crate::replay::{ReplayDomain, ReplaySelector};
use crate::replay_task::{send_causal_admitted, send_replay};
use loadtool_contract::bundle::{Bundle, PreparedRun};
use loadtool_contract::event::{
    Event, MessageKind, NotificationKind, NotificationStatus, Participant,
};
use loadtool_contract::generation_window::GenerationWindow;
use loadtool_contract::model::{ExecutionPlan, ReplayRule};

const PACER_CHANNEL_CAPACITY: usize = 2;
const RECORDER_CAPACITY: usize = 65_536;
const CAUSAL_HTTP_CAPACITY: usize = 16_384;
const ACTIVE_REQUEST_TIMEOUT: Duration = Duration::from_secs(5);
const CONNECTION_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Clone, Debug, Default)]
pub struct SimulationOptions {
    pub client_cert_root: Option<PathBuf>,
}

#[derive(Clone, Copy, Debug)]
struct ActiveWindow {
    generation_end: Instant,
    hard_deadline: Instant,
}

#[derive(Clone, Debug)]
struct Pair {
    payer: String,
    receiver: String,
}

#[derive(Clone, Debug)]
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

struct PreparedOriginalJob {
    job: PlannedOriginal,
    request: PreparedOriginal<PersistentPreparedRequest>,
    tracker: Option<Arc<PhaseTracker>>,
    work: PhaseWork,
    obligations: PreparedWarmupObligations,
}

struct StartedOriginalJob<F> {
    job: PlannedOriginal,
    started: StartedOriginal<F>,
    tracker: Option<Arc<PhaseTracker>>,
    work: PhaseWork,
}

struct PullSession {
    ispb: String,
    receiver_role: bool,
    client: PullClient,
}

struct Runtime {
    plan: Arc<ExecutionPlan>,
    planner: Arc<Planner>,
    identity: RunIdentity,
    clock: RunClock,
    pairs: BTreeMap<u32, Arc<Pair>>,
    http_clients: HashMap<String, Arc<PersistentHttp2Client>>,
    states: Arc<PaymentStates>,
    recorder: EventSender,
    tasks: TaskTracker,
    cancellation: CancellationToken,
    warmup_tracker: Arc<PhaseTracker>,
    warmup_outcomes: WarmupOutcomes,
    warmup_slots: u64,
    warmup_hard_deadline: Instant,
    active_window: RwLock<Option<ActiveWindow>>,
    accepting_work: AtomicBool,
    causal_capacity: Arc<CausalCapacity>,
    pacs008_replay: Option<(ReplaySelector, Duration)>,
    pacs002_replay: Option<(ReplaySelector, Duration)>,
    failure: RunFailure,
}

#[derive(Default)]
struct RunFailure {
    operational: Mutex<Option<String>>,
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

    fn operational_error(&self) -> Option<String> {
        self.operational
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

struct PreparedWarmupObligations {
    tracker: Option<Arc<PhaseTracker>>,
    rollback_count: u8,
}

impl PreparedWarmupObligations {
    fn register(tracker: Option<Arc<PhaseTracker>>, job: &PlannedOriginal) -> Result<Self> {
        let Some(tracker) = tracker else {
            return Ok(Self {
                tracker: None,
                rollback_count: 0,
            });
        };
        let count = 1
            + u8::from(job.pacs008_replay)
            + u8::from(job.pacs002_ordinal.is_some())
            + u8::from(job.pacs002_replay);
        let mut registered = 0u8;
        while registered < count {
            if let Err(error) = tracker.add() {
                rollback_tracker(&tracker, registered);
                return Err(error);
            }
            registered += 1;
        }
        Ok(Self {
            tracker: Some(tracker),
            rollback_count: count,
        })
    }

    fn transfer(&mut self) {
        self.rollback_count = 0;
    }
}

impl Drop for PreparedWarmupObligations {
    fn drop(&mut self) {
        if let Some(tracker) = &self.tracker {
            rollback_tracker(tracker, self.rollback_count);
        }
    }
}

fn rollback_tracker(tracker: &PhaseTracker, count: u8) {
    for _ in 0..count {
        if let Err(error) = tracker.done() {
            tracker.fail(error.to_string());
            break;
        }
    }
}

pub async fn run(bundle: Bundle, options: SimulationOptions) -> Result<GenerationWindow> {
    let PreparedRun { profile, plan } = bundle.load_prepared()?;
    bundle.prepare_outputs()?;
    let plan = Arc::new(plan);
    let planner = Arc::new(Planner::new(Arc::clone(&plan))?);
    let pairs = build_pairs(&plan)?;
    let participant_ispbs = participant_ispbs(&pairs);

    println!(
        "prewarming central transfer HTTP/2 clients: psps={}",
        participant_ispbs.len()
    );
    let central = Http2Config::new(
        &profile.connections.central_transfer.base_url,
        PathBuf::from(&profile.connections.central_transfer.ca_cert),
        options.client_cert_root.clone().unwrap_or_else(|| {
            PathBuf::from(&profile.connections.central_transfer.client_cert_root)
        }),
        profile.connections.central_transfer.server_name.clone(),
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
        PathBuf::from(&profile.connections.notification_gateway.ca_cert),
        options.client_cert_root.unwrap_or_else(|| {
            PathBuf::from(&profile.connections.notification_gateway.client_cert_root)
        }),
        profile.connections.notification_gateway.server_name.clone(),
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
        Arc::clone(&planner),
        identity.clone(),
        clock,
        RECORDER_CAPACITY,
    )?;
    let recorder_sender = recorder.sender()?;
    let warmup_slots = warmup_slots(&plan)?;
    let warmup_start = Instant::now()
        .checked_add(PREPARATION_LEAD)
        .context("warmup preparation deadline overflows Instant")?;
    let warmup_planned_end = warmup_start
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
        planner,
        identity,
        clock,
        pairs,
        http_clients,
        recorder: recorder_sender,
        tasks: TaskTracker::new(),
        cancellation: CancellationToken::new(),
        warmup_tracker: Arc::new(PhaseTracker::new()),
        warmup_outcomes: WarmupOutcomes::new(
            usize::try_from(warmup_slots)
                .context("warmup outcome state does not fit memory index")?,
        ),
        warmup_slots,
        warmup_hard_deadline,
        active_window: RwLock::new(None),
        accepting_work: AtomicBool::new(true),
        causal_capacity: Arc::new(CausalCapacity::new(CAUSAL_HTTP_CAPACITY)?),
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
        warmup_start,
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
        warmup_start + plan.load.warmup.bootstrap.duration,
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

    let active_start = Instant::now()
        .checked_add(PREPARATION_LEAD)
        .context("active preparation deadline overflows Instant")?;
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
    drop(runtime);
    recorder.close()?;

    if let Some(error) = operational_error {
        return Err(anyhow!(error));
    }
    let window = GenerationWindow {
        generation_started_at_ns: i64::try_from(clock.unix_nanos(warmup_start)?)
            .context("generation start exceeds i64 nanoseconds")?,
        active_started_at_ns: i64::try_from(clock.unix_nanos(active_start)?)
            .context("active start exceeds i64 nanoseconds")?,
        generation_ended_at_ns: i64::try_from(clock.unix_nanos(generation_end)?)
            .context("generation end exceeds i64 nanoseconds")?,
        replay_deadline_at_ns: i64::try_from(clock.unix_nanos(hard_deadline)?)
            .context("replay deadline exceeds i64 nanoseconds")?,
    };
    println!(
        "load generation completed: output={}",
        bundle.events_dir().display()
    );
    Ok(window)
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
    let (prepared_sender, prepared_receiver) = std::sync::mpsc::channel();
    let admission_runtime = Arc::clone(&runtime);
    let runtime_handle = tokio::runtime::Handle::current();
    let pacer = spawn_prepared_pacer(schedule, sender, prepared_receiver, move |bucket| {
        admit_prepared_bucket(
            Arc::clone(&admission_runtime),
            bucket.payload,
            &runtime_handle,
        );
    })?;
    let planner = Arc::clone(&runtime.planner);
    while let Some(descriptor) = receiver.recv().await {
        let mut jobs = Vec::with_capacity(
            usize::try_from(descriptor.request_count).expect("bucket request count fits usize"),
        );
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
            jobs.push(job);
        }
        let runtime_for_bucket = Arc::clone(&runtime);
        let prepared_for_bucket = prepared_sender.clone();
        let bucket_index = descriptor.bucket_index;
        runtime.tasks.spawn(async move {
            let prepared = prepare_original_bucket(runtime_for_bucket, jobs, warmup).await;
            let _ = prepared_for_bucket.send(PreparedBucket::new(bucket_index, prepared));
        });
    }
    drop(prepared_sender);
    let pacer_result = pacer
        .join()
        .map_err(|_| anyhow!("load-tool pacer thread panicked"))?;
    if warmup && pacer_result.missed_slots > 0 {
        runtime.warmup_tracker.fail(format!(
            "pacer missed {} warmup original slots",
            pacer_result.missed_slots
        ));
    }
    check_operational(&runtime)
}

async fn prepare_original_bucket(
    runtime: Arc<Runtime>,
    jobs: Vec<PlannedOriginal>,
    warmup: bool,
) -> Vec<PreparedOriginalJob> {
    let mut prepared = Vec::with_capacity(jobs.len());
    for job in jobs {
        let tracker = warmup.then(|| Arc::clone(&runtime.warmup_tracker));
        let work = PhaseWork(tracker.clone());
        let pair = match runtime.pairs.get(&job.pair_number) {
            Some(pair) => Arc::clone(pair),
            None => {
                runtime.failure.operational(
                    &runtime.cancellation,
                    format!("unknown pair {}", job.pair_number),
                );
                continue;
            }
        };
        let client = match runtime.http_clients.get(&pair.payer) {
            Some(client) => Arc::clone(client),
            None => {
                runtime.failure.operational(
                    &runtime.cancellation,
                    format!("missing HTTP/2 client for {}", pair.payer),
                );
                continue;
            }
        };
        let end_to_end_id = runtime.identity.end_to_end_id(job.sequence);
        let created_at = rfc3339_now();
        let result = prepare_original(client.as_ref(), job.sequence, job.bucket_deadline, || {
            pacs008(
                &end_to_end_id,
                &pair.payer,
                &pair.receiver,
                job.amount_cents,
                &created_at,
            )
        })
        .await;
        match result {
            Ok(AdmissionOutcome::Admitted(request)) => {
                match PreparedWarmupObligations::register(tracker.clone(), &job) {
                    Ok(obligations) => prepared.push(PreparedOriginalJob {
                        job,
                        request,
                        tracker,
                        work,
                        obligations,
                    }),
                    Err(error) => runtime.failure.operational(&runtime.cancellation, error),
                }
            }
            Ok(AdmissionOutcome::Missed(reason)) => {
                if let Some(tracker) = tracker {
                    tracker.fail(format!(
                        "warmup original {} missed admission: {reason:?}",
                        job.sequence
                    ));
                }
            }
            Err(error) => runtime.failure.operational(&runtime.cancellation, error),
        }
    }
    prepared
}

fn admit_prepared_bucket(
    runtime: Arc<Runtime>,
    prepared: Vec<PreparedOriginalJob>,
    runtime_handle: &tokio::runtime::Handle,
) {
    let mut started = Vec::with_capacity(prepared.len());
    for prepared in prepared {
        let PreparedOriginalJob {
            job,
            request,
            tracker,
            work,
            mut obligations,
        } = prepared;
        match admit_original(
            request,
            &runtime.states,
            job.request_timeout,
            job.hard_deadline,
        ) {
            Ok(AdmissionOutcome::Missed(reason)) => {
                if let Some(tracker) = &tracker {
                    tracker.fail(format!(
                        "warmup original {} missed admission: {reason:?}",
                        job.sequence
                    ));
                }
            }
            Ok(AdmissionOutcome::Admitted(request)) => {
                obligations.transfer();
                started.push(StartedOriginalJob {
                    job,
                    started: request,
                    tracker,
                    work,
                });
            }
            Err(error) => runtime.failure.operational(&runtime.cancellation, error),
        }
    }
    if started.is_empty() {
        return;
    }
    let task_runtime = Arc::clone(&runtime);
    runtime.tasks.spawn_on(
        async move {
            for started in started {
                handoff_started_original(Arc::clone(&task_runtime), started);
            }
        },
        runtime_handle,
    );
}

fn handoff_started_original<F>(runtime: Arc<Runtime>, started: StartedOriginalJob<F>)
where
    F: std::future::Future<Output = crate::http2::HttpAttempt> + Send + 'static,
{
    let StartedOriginalJob {
        job,
        started,
        tracker,
        work,
    } = started;
    if job.pacs008_replay {
        let delay = runtime
            .pacs008_replay
            .as_ref()
            .expect("selected replay has a rule")
            .1;
        spawn_replay(
            Arc::clone(&runtime),
            job.sequence,
            Participant::Payer,
            MessageKind::Pacs008,
            "/transfer",
            started.body().clone(),
            started.request_started_at(),
            job.hard_deadline,
            delay,
            false,
            tracker.clone(),
        );
    }
    let response_runtime = Arc::clone(&runtime);
    runtime.tasks.spawn(async move {
        finish_original(response_runtime, job, tracker, work, started).await;
    });
}

async fn finish_original<F>(
    runtime: Arc<Runtime>,
    job: PlannedOriginal,
    tracker: Option<Arc<PhaseTracker>>,
    _work: PhaseWork,
    started: StartedOriginal<F>,
) where
    F: std::future::Future<Output = crate::http2::HttpAttempt> + Send,
{
    let completion = match started.finish().await {
        Ok(completion) => completion,
        Err(error) => {
            runtime.failure.operational(&runtime.cancellation, error);
            return;
        }
    };
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
        if let Some(tracker) = tracker {
            tracker.fail(format!(
                "{} replay for sequence {sequence} was created after semantic shutdown",
                message.as_str()
            ));
        }
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
                eprintln!("generator capacity violation: {error}");
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
                    if let Some(tracker) = runtime.tracker_for(sequence) {
                        tracker.fail(format!(
                            "warmup PACS.002 for sequence {sequence} exceeded generator capacity: {error}"
                        ));
                    }
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
                if sequence >= runtime.warmup_slots || !runtime.states.is_committed(sequence) {
                    continue;
                }
                let payment = runtime.planner.payment(sequence)?;
                let expectation = &runtime.plan.scenarios[payment.scenario_index]
                    .expectations
                    .payer_notification;
                let Some(matches) = payer_outcome_match(
                    participant,
                    status,
                    reason_codes,
                    &expectation.status,
                    &expectation.reason_codes,
                ) else {
                    continue;
                };
                match runtime.warmup_outcomes.observe(sequence, matches) {
                    Some(WarmupObservation::MatchedFirst) => {
                        if let Some(tracker) = runtime.tracker_for(sequence) {
                            tracker.done()?;
                        }
                    }
                    Some(WarmupObservation::ContradictionFirst) => {
                        if let Some(tracker) = runtime.tracker_for(sequence) {
                            tracker.fail(format!(
                                "warmup payment {sequence} received contradictory payer outcome"
                            ));
                        }
                    }
                    Some(WarmupObservation::MatchedAgain)
                    | Some(WarmupObservation::ContradictionAgain)
                    | None => {}
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
    let payment = match runtime.planner.payment(sequence) {
        Ok(payment) => payment,
        Err(error) => {
            runtime.failure.operational(&runtime.cancellation, error);
            return;
        }
    };
    let replay_selected = payment.pacs002_ordinal.is_some_and(|ordinal| {
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
        let payment = self.planner.payment(sequence)?;
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

fn payer_outcome_match(
    participant: Participant,
    status: &str,
    reason_codes: &[String],
    expected_status: &str,
    expected_reason_codes: &[String],
) -> Option<bool> {
    (participant == Participant::Payer)
        .then(|| status == expected_status && same_reasons(reason_codes, expected_reason_codes))
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pacer_channel_holds_the_complete_preparation_window() {
        assert_eq!(PACER_CHANNEL_CAPACITY, 2);
    }

    #[test]
    fn only_payer_notifications_participate_in_business_outcome_matching() {
        assert_eq!(
            payer_outcome_match(Participant::Receiver, "ACSC", &[], "ACSC", &[],),
            None
        );
        assert_eq!(
            payer_outcome_match(Participant::Payer, "ACSC", &[], "ACSC", &[]),
            Some(true)
        );
        assert_eq!(
            payer_outcome_match(
                Participant::Payer,
                "RJCT",
                &["AM04".to_owned()],
                "ACSC",
                &[],
            ),
            Some(false)
        );
    }
}
