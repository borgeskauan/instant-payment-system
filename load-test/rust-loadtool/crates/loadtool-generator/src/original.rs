use std::future::Future;
use std::sync::Arc;
use std::time::{Duration, Instant};

use anyhow::{Result, anyhow};
use bytes::Bytes;

use crate::http2::{
    Http2Client, Http2Reservation, HttpAttempt, PersistentPreparedRequest, PreparedHttp2Request,
};
use crate::lifecycle::{offset_ns, rfc3339_now};
use crate::notification_flow::spawn_replay;
use crate::pacer::{PhaseSchedule, PreparedBucket, spawn_prepared_pacer};
use crate::payload::pacs008;
use crate::payment_state::PaymentStates;
use crate::phase_tracker::PhaseTracker;
use crate::runtime::{PhaseWork, Runtime};
use loadtool_contract::event::{Event, MessageKind, Participant};

const PACER_CHANNEL_CAPACITY: usize = 2;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AdmissionMiss {
    BeforePreparation,
    Http2Readiness,
    BeforeCommit,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AdmissionOutcome<T> {
    Missed(AdmissionMiss),
    Admitted(T),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct OriginalCompletion {
    pub attempt: HttpAttempt,
    pub request_started_at: Instant,
    pub request_done_at: Instant,
}

pub struct PreparedOriginal<R> {
    sequence: u64,
    body: Bytes,
    reservation: R,
    bucket_deadline: Instant,
}

pub struct StartedOriginal<F> {
    attempt: F,
    body: Bytes,
    request_started_at: Instant,
}

impl<F> StartedOriginal<F>
where
    F: Future<Output = HttpAttempt>,
{
    pub fn body(&self) -> &Bytes {
        &self.body
    }

    pub fn request_started_at(&self) -> Instant {
        self.request_started_at
    }

    pub async fn finish(self) -> Result<OriginalCompletion> {
        let attempt = self.attempt.await;
        let request_done_at = Instant::now();
        if attempt.used_http1() {
            return Err(anyhow!("central transfer response did not use HTTP/2"));
        }
        Ok(OriginalCompletion {
            attempt,
            request_started_at: self.request_started_at,
            request_done_at,
        })
    }
}

pub async fn prepare_original<C, Build>(
    client: &C,
    sequence: u64,
    bucket_deadline: Instant,
    build_payload: Build,
) -> Result<AdmissionOutcome<PreparedOriginal<<C::Reservation as Http2Reservation>::Prepared>>>
where
    C: Http2Client,
    Build: FnOnce() -> Result<Bytes>,
{
    if Instant::now() >= bucket_deadline {
        return Ok(AdmissionOutcome::Missed(AdmissionMiss::BeforePreparation));
    }
    let body = build_payload()?;
    let Some(reservation) = client.reserve_until(bucket_deadline).await? else {
        return Ok(AdmissionOutcome::Missed(AdmissionMiss::Http2Readiness));
    };
    if Instant::now() >= bucket_deadline {
        return Ok(AdmissionOutcome::Missed(AdmissionMiss::Http2Readiness));
    }
    let request = reservation.prepare("/transfer", body.clone())?;
    Ok(AdmissionOutcome::Admitted(PreparedOriginal {
        sequence,
        body,
        reservation: request,
        bucket_deadline,
    }))
}

pub fn admit_original<R>(
    prepared: PreparedOriginal<R>,
    states: &PaymentStates,
    request_timeout: Duration,
    hard_deadline: Instant,
) -> Result<AdmissionOutcome<StartedOriginal<impl Future<Output = HttpAttempt> + Send + use<R>>>>
where
    R: PreparedHttp2Request,
{
    if Instant::now() >= prepared.bucket_deadline {
        return Ok(AdmissionOutcome::Missed(AdmissionMiss::BeforeCommit));
    }

    if !states.commit(prepared.sequence) {
        return Err(anyhow!(
            "payment sequence {} was already committed",
            prepared.sequence
        ));
    }
    let request_started_at = Instant::now();
    let request_deadline = request_started_at
        .checked_add(request_timeout)
        .unwrap_or(hard_deadline)
        .min(hard_deadline);
    let attempt = prepared.reservation.start(request_deadline);
    Ok(AdmissionOutcome::Admitted(StartedOriginal {
        attempt,
        body: prepared.body,
        request_started_at,
    }))
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

#[allow(clippy::too_many_arguments)]
pub(crate) async fn run_generation_phase(
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
    runtime.check_operational()
}

async fn prepare_original_bucket(
    runtime: Arc<Runtime>,
    jobs: Vec<PlannedOriginal>,
    warmup: bool,
) -> Vec<PreparedOriginalJob> {
    let mut prepared = Vec::with_capacity(jobs.len());
    for job in jobs {
        let tracker = warmup.then(|| Arc::clone(&runtime.warmup_tracker));
        let work = PhaseWork::new(tracker.clone());
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
        let client = match runtime.http_clients.get(pair.payer()) {
            Some(client) => Arc::clone(client),
            None => {
                runtime.failure.operational(
                    &runtime.cancellation,
                    format!("missing HTTP/2 client for {}", pair.payer()),
                );
                continue;
            }
        };
        let end_to_end_id = runtime.identity.end_to_end_id(job.sequence);
        let created_at = rfc3339_now();
        let result = prepare_original(client.as_ref(), job.sequence, job.bucket_deadline, || {
            pacs008(
                &end_to_end_id,
                pair.payer(),
                pair.receiver(),
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

#[cfg(test)]
mod generator_tests {
    use super::*;

    #[test]
    fn pacer_channel_holds_the_complete_preparation_window() {
        assert_eq!(PACER_CHANNEL_CAPACITY, 2);
    }
}
