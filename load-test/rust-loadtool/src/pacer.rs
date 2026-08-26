use std::collections::BTreeSet;
use std::hint::spin_loop;
use std::sync::mpsc::{Receiver, RecvTimeoutError};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use anyhow::{Result, anyhow, bail};
use serde::Serialize;
use tokio::sync::mpsc;

use crate::generator_metrics::{DurationHistogram, HistogramSummary, PacerDeadlineMisses};

const BUCKET: Duration = Duration::from_millis(10);
const SPIN_TAIL: Duration = Duration::from_micros(50);
pub const PREPARATION_LEAD: Duration = Duration::from_millis(20);

#[derive(Clone, Debug)]
pub struct BucketDescriptor {
    pub bucket_index: u64,
    pub first_sequence: u64,
    pub request_count: u64,
    pub preparation_start: Instant,
    pub bucket_start: Instant,
    pub bucket_deadline: Instant,
}

#[derive(Debug)]
pub struct PreparedBucket<T> {
    pub bucket_index: u64,
    pub payload: T,
}

impl<T> PreparedBucket<T> {
    pub fn new(bucket_index: u64, payload: T) -> Self {
        Self {
            bucket_index,
            payload,
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub struct PhaseSchedule {
    start: Instant,
    end: Instant,
    buckets: u64,
    rate: u64,
    first_sequence: u64,
    planned_slots: u64,
}

impl PhaseSchedule {
    pub fn new(start: Instant, duration: Duration, rate: u64, first_sequence: u64) -> Result<Self> {
        if rate == 0 {
            bail!("phase rate must be positive");
        }
        if duration.is_zero() || duration.as_nanos() % BUCKET.as_nanos() != 0 {
            bail!("phase duration must be a positive whole number of milliseconds");
        }
        let buckets = u64::try_from(duration.as_nanos() / BUCKET.as_nanos())
            .map_err(|_| anyhow!("phase bucket count overflows u64"))?;
        let planned_slots = cumulative_slots(rate, buckets)?;
        first_sequence
            .checked_add(planned_slots)
            .ok_or_else(|| anyhow!("phase sequence range overflows"))?;
        let end = start
            .checked_add(duration)
            .ok_or_else(|| anyhow!("phase deadline overflows Instant"))?;
        Ok(Self {
            start,
            end,
            buckets,
            rate,
            first_sequence,
            planned_slots,
        })
    }

    pub fn start(&self) -> Instant {
        self.start
    }

    pub fn end(&self) -> Instant {
        self.end
    }

    pub fn first_sequence(&self) -> u64 {
        self.first_sequence
    }

    pub fn planned_slots(&self) -> u64 {
        self.planned_slots
    }

    pub fn descriptor(&self, bucket_index: u64) -> Option<BucketDescriptor> {
        if bucket_index >= self.buckets {
            return None;
        }
        let preceding = cumulative_slots(self.rate, bucket_index).ok()?;
        let through = cumulative_slots(self.rate, bucket_index + 1).ok()?;
        let bucket_start = self.start.checked_add(bucket_offset(bucket_index)?)?;
        Some(BucketDescriptor {
            bucket_index,
            first_sequence: self.first_sequence.checked_add(preceding)?,
            request_count: through - preceding,
            preparation_start: bucket_start
                .checked_sub(PREPARATION_LEAD)
                .unwrap_or(bucket_start),
            bucket_start,
            bucket_deadline: bucket_start.checked_add(BUCKET)?,
        })
    }

    fn slots_between(&self, from_bucket: u64, to_bucket: u64) -> u64 {
        let from = cumulative_slots(self.rate, from_bucket)
            .expect("validated phase arithmetic remains in range");
        let to = cumulative_slots(self.rate, to_bucket)
            .expect("validated phase arithmetic remains in range");
        to - from
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct CursorAdvance {
    pub next_bucket: Option<u64>,
    pub missed_slots: u64,
}

pub fn advance_cursor(schedule: &PhaseSchedule, cursor: u64, now: Instant) -> CursorAdvance {
    if cursor >= schedule.buckets {
        return CursorAdvance {
            next_bucket: None,
            missed_slots: 0,
        };
    }
    if now < schedule.start {
        return CursorAdvance {
            next_bucket: Some(cursor),
            missed_slots: 0,
        };
    }
    let elapsed_buckets =
        u64::try_from(now.duration_since(schedule.start).as_nanos() / BUCKET.as_nanos())
            .unwrap_or(u64::MAX)
            .min(schedule.buckets);
    let next = cursor.max(elapsed_buckets);
    let missed_slots = schedule.slots_between(cursor, next);
    CursorAdvance {
        next_bucket: (next < schedule.buckets).then_some(next),
        missed_slots,
    }
}

#[derive(Clone, Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PacerMetrics {
    pub planned_slots: u64,
    pub dispatched_slots: u64,
    pub missed_slots: u64,
    pub missed_cursor_skip: u64,
    pub missed_expired_before_dispatch: u64,
    pub missed_channel_full: u64,
    pub missed_preparation_not_ready: u64,
    pub spin_wall_time_ns: u64,
    pub pacer_lateness: HistogramSummary,
    pub deadline_misses: PacerDeadlineMisses,
    pub sleep_wake_lateness: HistogramSummary,
    pub channel_closed: bool,
}

impl PacerMetrics {
    fn record_cursor_skip(&mut self, count: u64) {
        self.missed_slots = self.missed_slots.saturating_add(count);
        self.missed_cursor_skip = self.missed_cursor_skip.saturating_add(count);
    }

    fn record_expired_before_dispatch(&mut self, count: u64) {
        self.missed_slots = self.missed_slots.saturating_add(count);
        self.missed_expired_before_dispatch =
            self.missed_expired_before_dispatch.saturating_add(count);
    }

    fn record_channel_full(&mut self, count: u64) {
        self.missed_slots = self.missed_slots.saturating_add(count);
        self.missed_channel_full = self.missed_channel_full.saturating_add(count);
    }

    fn record_preparation_not_ready(&mut self, count: u64) {
        self.missed_slots = self.missed_slots.saturating_add(count);
        self.missed_preparation_not_ready = self.missed_preparation_not_ready.saturating_add(count);
    }
}

pub fn spawn_prepared_pacer<T, Admit>(
    schedule: PhaseSchedule,
    descriptor_sender: mpsc::Sender<BucketDescriptor>,
    prepared_receiver: Receiver<PreparedBucket<T>>,
    admit: Admit,
) -> Result<JoinHandle<PacerMetrics>>
where
    T: Send + 'static,
    Admit: FnMut(PreparedBucket<T>) + Send + 'static,
{
    thread::Builder::new()
        .name("loadtool-pacer".to_owned())
        .spawn(move || run_prepared_pacer(schedule, descriptor_sender, prepared_receiver, admit))
        .map_err(Into::into)
}

fn run_prepared_pacer<T, Admit>(
    schedule: PhaseSchedule,
    sender: mpsc::Sender<BucketDescriptor>,
    prepared_receiver: Receiver<PreparedBucket<T>>,
    mut admit: Admit,
) -> PacerMetrics
where
    T: Send + 'static,
    Admit: FnMut(PreparedBucket<T>),
{
    let mut metrics = PacerMetrics {
        planned_slots: schedule.planned_slots,
        ..PacerMetrics::default()
    };
    let mut lateness = DurationHistogram::new();
    let mut sleep_wake_lateness = DurationHistogram::new();
    let lead_buckets = u64::try_from(PREPARATION_LEAD.as_nanos() / BUCKET.as_nanos())
        .expect("fixed preparation lead fits u64");
    let mut next_prepare = 0u64;
    let mut cursor = 0u64;
    let mut pending = (0..lead_buckets + 1)
        .map(|_| None)
        .collect::<Vec<Option<PreparedBucket<T>>>>();
    let mut dispatched = BTreeSet::<u64>::new();

    prepare_descriptors_through(
        &schedule,
        lead_buckets.min(schedule.buckets),
        &sender,
        &mut next_prepare,
        &mut dispatched,
        &mut metrics,
    );

    while cursor < schedule.buckets && !metrics.channel_closed {
        let advance = advance_cursor(&schedule, cursor, Instant::now());
        let Some(bucket) = advance.next_bucket else {
            if next_prepare < schedule.buckets {
                metrics.record_cursor_skip(schedule.slots_between(next_prepare, schedule.buckets));
            }
            for skipped in cursor..schedule.buckets {
                if dispatched.remove(&skipped)
                    && let Some(descriptor) = schedule.descriptor(skipped)
                {
                    metrics.record_cursor_skip(descriptor.request_count);
                }
            }
            break;
        };
        if bucket > next_prepare {
            metrics.record_cursor_skip(schedule.slots_between(next_prepare, bucket));
            next_prepare = bucket;
        }
        for skipped in cursor..bucket {
            if dispatched.remove(&skipped)
                && let Some(descriptor) = schedule.descriptor(skipped)
            {
                metrics.record_cursor_skip(descriptor.request_count);
            }
        }
        cursor = bucket;

        let timing = schedule
            .descriptor(bucket)
            .expect("live bucket has a descriptor");
        let wait = wait_until(timing.bucket_start);
        record_wait_metrics(
            &mut metrics,
            &mut sleep_wake_lateness,
            timing.bucket_deadline,
            wait,
        );
        if timing.request_count > 0 {
            lateness.record_ns(nanos(
                wait.completed_at
                    .saturating_duration_since(timing.bucket_start),
            ));
        }

        if dispatched.remove(&bucket) {
            match take_prepared_until(
                bucket,
                timing.bucket_deadline,
                &prepared_receiver,
                &mut pending,
            ) {
                Some(prepared) => admit(prepared),
                None => {
                    metrics.record_preparation_not_ready(timing.request_count);
                }
            }
        }
        cursor += 1;

        prepare_descriptors_through(
            &schedule,
            cursor.saturating_add(lead_buckets).min(schedule.buckets),
            &sender,
            &mut next_prepare,
            &mut dispatched,
            &mut metrics,
        );
    }

    metrics.pacer_lateness = lateness.summary();
    metrics.sleep_wake_lateness = sleep_wake_lateness.summary();
    metrics
}

fn take_prepared_until<T>(
    bucket_index: u64,
    deadline: Instant,
    receiver: &Receiver<PreparedBucket<T>>,
    pending: &mut [Option<PreparedBucket<T>>],
) -> Option<PreparedBucket<T>> {
    if let Some(prepared) = take_pending(bucket_index, pending) {
        return Some(prepared);
    }
    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return None;
        }
        match receiver.recv_timeout(remaining) {
            Ok(prepared) if prepared.bucket_index == bucket_index => return Some(prepared),
            Ok(prepared) if prepared.bucket_index > bucket_index => {
                store_pending(prepared, pending);
            }
            Ok(_) => {}
            Err(RecvTimeoutError::Timeout | RecvTimeoutError::Disconnected) => return None,
        }
    }
}

fn take_pending<T>(
    bucket_index: u64,
    pending: &mut [Option<PreparedBucket<T>>],
) -> Option<PreparedBucket<T>> {
    let width = u64::try_from(pending.len()).expect("preparation window fits u64");
    let slot = usize::try_from(bucket_index % width).expect("preparation slot fits usize");
    pending[slot]
        .take()
        .filter(|prepared| prepared.bucket_index == bucket_index)
}

fn store_pending<T>(prepared: PreparedBucket<T>, pending: &mut [Option<PreparedBucket<T>>]) {
    let width = u64::try_from(pending.len()).expect("preparation window fits u64");
    let slot = usize::try_from(prepared.bucket_index % width).expect("preparation slot fits usize");
    debug_assert!(pending[slot].is_none());
    pending[slot] = Some(prepared);
}

fn prepare_descriptors_through(
    schedule: &PhaseSchedule,
    target: u64,
    sender: &mpsc::Sender<BucketDescriptor>,
    next_prepare: &mut u64,
    dispatched: &mut BTreeSet<u64>,
    metrics: &mut PacerMetrics,
) {
    while *next_prepare < target && !metrics.channel_closed {
        let descriptor = schedule
            .descriptor(*next_prepare)
            .expect("prepared bucket has a descriptor");
        *next_prepare += 1;
        if descriptor.request_count == 0 {
            continue;
        }
        let wait = wait_until(descriptor.preparation_start);
        metrics.spin_wall_time_ns = metrics
            .spin_wall_time_ns
            .saturating_add(wait.spin_wall_time_ns);
        if Instant::now() >= descriptor.bucket_deadline {
            metrics.record_expired_before_dispatch(descriptor.request_count);
            continue;
        }
        let request_count = descriptor.request_count;
        match sender.try_send(descriptor) {
            Ok(()) => {
                metrics.dispatched_slots += request_count;
                dispatched.insert(*next_prepare - 1);
            }
            Err(mpsc::error::TrySendError::Full(descriptor)) => {
                metrics.record_channel_full(descriptor.request_count);
            }
            Err(mpsc::error::TrySendError::Closed(_)) => {
                metrics.channel_closed = true;
                metrics.missed_slots += schedule.slots_between(*next_prepare - 1, schedule.buckets);
            }
        }
    }
}

fn record_wait_metrics(
    metrics: &mut PacerMetrics,
    sleep_wake_lateness: &mut DurationHistogram,
    deadline: Instant,
    wait: WaitTiming,
) {
    metrics.spin_wall_time_ns = metrics
        .spin_wall_time_ns
        .saturating_add(wait.spin_wall_time_ns);
    if let Some(value) = wait.sleep_wake_lateness_ns() {
        sleep_wake_lateness.record_ns(value);
    }
    match classify_wait_deadline_miss(
        deadline,
        wait.entered_at,
        wait.sleep_returned_at,
        wait.completed_at,
    ) {
        Some(WaitDeadlineMiss::Entered) => metrics.deadline_misses.entered_after_deadline += 1,
        Some(WaitDeadlineMiss::SleepReturned) => {
            metrics.deadline_misses.sleep_returned_after_deadline += 1;
        }
        Some(WaitDeadlineMiss::SpinCompleted) => {
            metrics.deadline_misses.spin_completed_after_deadline += 1;
        }
        None => {}
    }
}

fn nanos(duration: Duration) -> u64 {
    u64::try_from(duration.as_nanos()).unwrap_or(u64::MAX)
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum WaitDeadlineMiss {
    Entered,
    SleepReturned,
    SpinCompleted,
}

#[derive(Clone, Copy, Debug)]
struct WaitTiming {
    entered_at: Instant,
    planned_spin_start: Option<Instant>,
    sleep_returned_at: Option<Instant>,
    completed_at: Instant,
    spin_wall_time_ns: u64,
}

impl WaitTiming {
    fn sleep_wake_lateness_ns(self) -> Option<u64> {
        Some(nanos(
            self.sleep_returned_at?
                .saturating_duration_since(self.planned_spin_start?),
        ))
    }
}

fn classify_wait_deadline_miss(
    deadline: Instant,
    entered_at: Instant,
    sleep_returned_at: Option<Instant>,
    completed_at: Instant,
) -> Option<WaitDeadlineMiss> {
    if entered_at >= deadline {
        Some(WaitDeadlineMiss::Entered)
    } else if sleep_returned_at.is_some_and(|value| value >= deadline) {
        Some(WaitDeadlineMiss::SleepReturned)
    } else if completed_at >= deadline {
        Some(WaitDeadlineMiss::SpinCompleted)
    } else {
        None
    }
}

fn wait_until(target: Instant) -> WaitTiming {
    let entered_at = Instant::now();
    if entered_at >= target {
        return WaitTiming {
            entered_at,
            planned_spin_start: None,
            sleep_returned_at: None,
            completed_at: entered_at,
            spin_wall_time_ns: 0,
        };
    }
    let remaining = target.duration_since(entered_at);
    let mut planned_spin_start = None;
    let mut sleep_returned_at = None;
    if remaining > SPIN_TAIL {
        planned_spin_start = target.checked_sub(SPIN_TAIL);
        thread::sleep(remaining - SPIN_TAIL);
        sleep_returned_at = Some(Instant::now());
    }
    let spin_started = Instant::now();
    while Instant::now() < target {
        spin_loop();
    }
    let completed_at = Instant::now();
    WaitTiming {
        entered_at,
        planned_spin_start,
        sleep_returned_at,
        completed_at,
        spin_wall_time_ns: nanos(completed_at.saturating_duration_since(spin_started)),
    }
}

fn cumulative_slots(rate: u64, buckets: u64) -> Result<u64> {
    let slots = u128::from(buckets)
        .checked_mul(u128::from(rate))
        .ok_or_else(|| anyhow!("phase slot arithmetic overflows"))?
        .checked_mul(BUCKET.as_nanos())
        .ok_or_else(|| anyhow!("phase slot arithmetic overflows"))?
        / Duration::from_secs(1).as_nanos();
    u64::try_from(slots).map_err(|_| anyhow!("phase slot count overflows u64"))
}

fn bucket_offset(bucket_index: u64) -> Option<Duration> {
    let nanos = BUCKET.as_nanos().checked_mul(u128::from(bucket_index))?;
    Some(Duration::from_nanos(u64::try_from(nanos).ok()?))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pacer_starts_its_spin_fifty_microseconds_before_the_boundary() {
        assert_eq!(SPIN_TAIL, Duration::from_micros(50));
    }

    #[test]
    fn wait_deadline_misses_identify_the_stage_that_crossed_the_deadline() {
        let origin = Instant::now();
        let deadline = origin + Duration::from_millis(10);

        assert_eq!(
            classify_wait_deadline_miss(deadline, deadline, None, deadline),
            Some(WaitDeadlineMiss::Entered)
        );
        assert_eq!(
            classify_wait_deadline_miss(
                deadline,
                origin,
                Some(deadline + Duration::from_nanos(1)),
                deadline + Duration::from_nanos(1),
            ),
            Some(WaitDeadlineMiss::SleepReturned)
        );
        assert_eq!(
            classify_wait_deadline_miss(
                deadline,
                origin,
                Some(origin + Duration::from_millis(9)),
                deadline + Duration::from_nanos(1),
            ),
            Some(WaitDeadlineMiss::SpinCompleted)
        );
        assert_eq!(
            classify_wait_deadline_miss(
                deadline,
                origin,
                Some(origin + Duration::from_millis(9)),
                deadline - Duration::from_nanos(1),
            ),
            None
        );
    }
}
