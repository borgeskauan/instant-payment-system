use std::collections::{BTreeMap, HashMap};
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::AtomicBool;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result, anyhow, bail};
use tokio_util::sync::CancellationToken;
use tokio_util::task::TaskTracker;

use crate::causal::CausalCapacity;
use crate::clock::RunClock;
use crate::http2::Http2Config;
use crate::lifecycle::{ActiveBoundaries, WarmupBoundaries, finish_active};
use crate::notification_flow::{PullSession, pull_loop};
use crate::original::run_generation_phase;
use crate::pacer::PREPARATION_LEAD;
use crate::payment_state::PaymentStates;
use crate::phase_tracker::{PhaseTracker, WarmupOutcomes};
use crate::planner::{Planner, RunIdentity};
use crate::pull::PullClientConfig;
use crate::recorder::EventRecorder;
use crate::replay::{ReplayDomain, ReplaySelector};
use crate::runtime::{ACTIVE_REQUEST_TIMEOUT, Pair, RunFailure, Runtime};
use loadtool_contract::bundle::{Bundle, PreparedRun};
use loadtool_contract::generation_window::GenerationWindow;
use loadtool_contract::model::{ExecutionPlan, ReplayRule};

pub use crate::lifecycle::http_deadline;

const RECORDER_CAPACITY: usize = 65_536;
const CAUSAL_HTTP_CAPACITY: usize = 16_384;
const CONNECTION_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Clone, Debug, Default)]
pub struct SimulationOptions {
    pub client_cert_root: Option<PathBuf>,
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
        pull_sessions.push(PullSession::new(
            ispb.clone(),
            receiver_role,
            gateway.connect(&ispb).await?,
        ));
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
    let warmup = WarmupBoundaries::new(warmup_start, &plan)?;
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
        warmup_hard_deadline: warmup.hard_deadline,
        active_window: Runtime::active_window_cell(),
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
        warmup.hard_deadline,
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
        warmup.hard_deadline,
        true,
    )
    .await?;
    runtime.warmup_tracker.close_generation();
    println!("warmup generation finished; waiting for observable work");
    runtime
        .warmup_tracker
        .wait(warmup.hard_deadline)
        .await
        .context("warmup completion gate")?;
    runtime.check_operational()?;

    let active_start = Instant::now()
        .checked_add(PREPARATION_LEAD)
        .context("active preparation deadline overflows Instant")?;
    let active = ActiveBoundaries::new(active_start, &plan)?;
    runtime.set_active_window(active.generation_end, active.hard_deadline);
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
        active.hard_deadline,
        false,
    )
    .await?;

    println!("generation finished; observing fixed drain");
    finish_active(&runtime, active.hard_deadline).await;

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
        generation_ended_at_ns: i64::try_from(clock.unix_nanos(active.generation_end)?)
            .context("generation end exceeds i64 nanoseconds")?,
        replay_deadline_at_ns: i64::try_from(clock.unix_nanos(active.hard_deadline)?)
            .context("replay deadline exceeds i64 nanoseconds")?,
    };
    println!(
        "load generation completed: output={}",
        bundle.events_dir().display()
    );
    Ok(window)
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
                Arc::new(Pair::new(
                    format!("10{number:06}"),
                    format!("20{number:06}"),
                ))
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
        values.push(pair.payer().to_owned());
        values.push(pair.receiver().to_owned());
    }
    values.sort();
    values.dedup();
    values
}

fn pull_specs(pairs: &BTreeMap<u32, Arc<Pair>>) -> Vec<(String, bool)> {
    let mut specs = BTreeMap::new();
    for pair in pairs.values() {
        specs.insert(pair.payer().to_owned(), false);
        specs.insert(pair.receiver().to_owned(), true);
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
