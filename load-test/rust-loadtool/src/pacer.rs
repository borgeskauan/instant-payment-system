use std::collections::VecDeque;
use std::hint::spin_loop;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use anyhow::{Result, anyhow, bail};
use serde::Serialize;
use tokio::sync::Notify;
use tokio::sync::mpsc;

use crate::generator_metrics::{DurationHistogram, HistogramSummary};

const BUCKET: Duration = Duration::from_millis(1);
const SPIN_TAIL: Duration = Duration::from_micros(50);
pub const PREPARATION_LEAD: Duration = Duration::from_millis(20);

#[derive(Debug)]
pub struct BucketGate {
    released: AtomicBool,
    notify: Notify,
}

impl BucketGate {
    fn new() -> Self {
        Self {
            released: AtomicBool::new(false),
            notify: Notify::new(),
        }
    }

    pub fn pending() -> Arc<Self> {
        Arc::new(Self::new())
    }

    pub fn released() -> Arc<Self> {
        let gate = Self::pending();
        gate.release();
        gate
    }

    pub fn release(&self) {
        if !self.released.swap(true, Ordering::AcqRel) {
            self.notify.notify_waiters();
        }
    }

    pub async fn wait(&self) {
        loop {
            if self.released.load(Ordering::Acquire) {
                return;
            }
            let notified = self.notify.notified();
            if self.released.load(Ordering::Acquire) {
                return;
            }
            notified.await;
        }
    }
}

#[derive(Clone, Debug)]
pub struct BucketDescriptor {
    pub bucket_index: u64,
    pub first_sequence: u64,
    pub request_count: u64,
    pub preparation_start: Instant,
    pub bucket_start: Instant,
    pub bucket_deadline: Instant,
    pub gate: Arc<BucketGate>,
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
        let buckets = u64::try_from(duration.as_millis())
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
        let bucket_start = self
            .start
            .checked_add(Duration::from_millis(bucket_index))?;
        Some(BucketDescriptor {
            bucket_index,
            first_sequence: self.first_sequence.checked_add(preceding)?,
            request_count: through - preceding,
            preparation_start: bucket_start
                .checked_sub(PREPARATION_LEAD)
                .unwrap_or(bucket_start),
            bucket_start,
            bucket_deadline: bucket_start.checked_add(BUCKET)?,
            gate: Arc::new(BucketGate::new()),
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
    let elapsed_buckets = u64::try_from(now.duration_since(schedule.start).as_millis())
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
    pub spin_wall_time_ns: u64,
    pub pacer_lateness: HistogramSummary,
    pub channel_closed: bool,
}

pub fn spawn_pacer(
    schedule: PhaseSchedule,
    sender: mpsc::Sender<BucketDescriptor>,
) -> Result<JoinHandle<PacerMetrics>> {
    thread::Builder::new()
        .name("loadtool-pacer".to_owned())
        .spawn(move || run_pacer(schedule, sender))
        .map_err(Into::into)
}

fn run_pacer(schedule: PhaseSchedule, sender: mpsc::Sender<BucketDescriptor>) -> PacerMetrics {
    let mut metrics = PacerMetrics {
        planned_slots: schedule.planned_slots,
        ..PacerMetrics::default()
    };
    let mut lateness = DurationHistogram::new();
    let lead_buckets =
        u64::try_from(PREPARATION_LEAD.as_millis()).expect("fixed preparation lead fits u64");
    let mut next_prepare = 0u64;
    let mut cursor = 0u64;
    let mut gates = VecDeque::<(u64, Arc<BucketGate>)>::with_capacity(
        usize::try_from(lead_buckets).expect("fixed preparation lead fits usize"),
    );

    prepare_through(
        &schedule,
        lead_buckets.min(schedule.buckets),
        &sender,
        &mut next_prepare,
        &mut gates,
        &mut metrics,
    );

    while cursor < schedule.buckets && !metrics.channel_closed {
        let advance = advance_cursor(&schedule, cursor, Instant::now());
        let Some(bucket) = advance.next_bucket else {
            if next_prepare < schedule.buckets {
                metrics.missed_slots += schedule.slots_between(next_prepare, schedule.buckets);
            }
            break;
        };
        if bucket > next_prepare {
            metrics.missed_slots += schedule.slots_between(next_prepare, bucket);
            next_prepare = bucket;
        }
        release_before(&mut gates, bucket);
        cursor = bucket;

        let timing = schedule
            .descriptor(bucket)
            .expect("live bucket has a descriptor");
        metrics.spin_wall_time_ns = metrics
            .spin_wall_time_ns
            .saturating_add(wait_until(timing.bucket_start));
        let released_at = Instant::now();
        release_through(&mut gates, bucket);
        if timing.request_count > 0 {
            lateness.record_ns(nanos(
                released_at.saturating_duration_since(timing.bucket_start),
            ));
        }
        cursor += 1;

        prepare_through(
            &schedule,
            cursor.saturating_add(lead_buckets).min(schedule.buckets),
            &sender,
            &mut next_prepare,
            &mut gates,
            &mut metrics,
        );
    }

    gates.iter().for_each(|(_, gate)| gate.release());

    metrics.pacer_lateness = lateness.summary();
    metrics
}

#[allow(clippy::too_many_arguments)]
fn prepare_through(
    schedule: &PhaseSchedule,
    target: u64,
    sender: &mpsc::Sender<BucketDescriptor>,
    next_prepare: &mut u64,
    gates: &mut VecDeque<(u64, Arc<BucketGate>)>,
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
        metrics.spin_wall_time_ns = metrics
            .spin_wall_time_ns
            .saturating_add(wait_until(descriptor.preparation_start));
        if Instant::now() >= descriptor.bucket_deadline {
            metrics.missed_slots += descriptor.request_count;
            continue;
        }
        let gate = Arc::clone(&descriptor.gate);
        let request_count = descriptor.request_count;
        match sender.try_send(descriptor) {
            Ok(()) => {
                metrics.dispatched_slots += request_count;
                gates.push_back((*next_prepare - 1, gate));
            }
            Err(mpsc::error::TrySendError::Full(descriptor)) => {
                metrics.missed_slots += descriptor.request_count;
            }
            Err(mpsc::error::TrySendError::Closed(_)) => {
                metrics.channel_closed = true;
                metrics.missed_slots += schedule.slots_between(*next_prepare - 1, schedule.buckets);
            }
        }
    }
}

fn release_before(gates: &mut VecDeque<(u64, Arc<BucketGate>)>, bucket: u64) {
    while gates.front().is_some_and(|(index, _)| *index < bucket) {
        if let Some((_, gate)) = gates.pop_front() {
            gate.release();
        }
    }
}

fn release_through(gates: &mut VecDeque<(u64, Arc<BucketGate>)>, bucket: u64) {
    while gates.front().is_some_and(|(index, _)| *index <= bucket) {
        if let Some((_, gate)) = gates.pop_front() {
            gate.release();
        }
    }
}

fn nanos(duration: Duration) -> u64 {
    u64::try_from(duration.as_nanos()).unwrap_or(u64::MAX)
}

fn wait_until(target: Instant) -> u64 {
    let now = Instant::now();
    if now >= target {
        return 0;
    }
    let remaining = target.duration_since(now);
    if remaining > SPIN_TAIL {
        thread::sleep(remaining - SPIN_TAIL);
    }
    let spin_started = Instant::now();
    while Instant::now() < target {
        spin_loop();
    }
    u64::try_from(spin_started.elapsed().as_nanos()).unwrap_or(u64::MAX)
}

fn cumulative_slots(rate: u64, buckets: u64) -> Result<u64> {
    let slots = u128::from(buckets)
        .checked_mul(u128::from(rate))
        .ok_or_else(|| anyhow!("phase slot arithmetic overflows"))?
        / 1000;
    u64::try_from(slots).map_err(|_| anyhow!("phase slot count overflows u64"))
}
