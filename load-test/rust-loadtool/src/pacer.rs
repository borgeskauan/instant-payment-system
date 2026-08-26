use std::hint::spin_loop;
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use anyhow::{Result, anyhow, bail};
use serde::Serialize;
use tokio::sync::mpsc;

use crate::generator_metrics::{DurationHistogram, HistogramSummary};

const BUCKET: Duration = Duration::from_millis(1);
const SPIN_TAIL: Duration = Duration::from_micros(50);

#[derive(Clone, Copy, Debug)]
pub struct BucketDescriptor {
    pub bucket_index: u64,
    pub first_sequence: u64,
    pub request_count: u64,
    pub bucket_start: Instant,
    pub bucket_deadline: Instant,
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
    let mut cursor = 0u64;

    while cursor < schedule.buckets {
        let advance = advance_cursor(&schedule, cursor, Instant::now());
        metrics.missed_slots += advance.missed_slots;
        let Some(bucket) = advance.next_bucket else {
            break;
        };
        cursor = bucket;
        let descriptor = schedule
            .descriptor(bucket)
            .expect("live bucket has a descriptor");

        metrics.spin_wall_time_ns = metrics
            .spin_wall_time_ns
            .saturating_add(wait_until(descriptor.bucket_start));
        let dispatch_time = Instant::now();
        if dispatch_time >= descriptor.bucket_deadline {
            metrics.missed_slots += descriptor.request_count;
            cursor += 1;
            continue;
        }

        let late_ns = u64::try_from(
            dispatch_time
                .saturating_duration_since(descriptor.bucket_start)
                .as_nanos(),
        )
        .unwrap_or(u64::MAX);
        lateness.record_ns(late_ns);
        match sender.try_send(descriptor) {
            Ok(()) => metrics.dispatched_slots += descriptor.request_count,
            Err(mpsc::error::TrySendError::Full(_)) => {
                metrics.missed_slots += descriptor.request_count;
            }
            Err(mpsc::error::TrySendError::Closed(_)) => {
                metrics.channel_closed = true;
                metrics.missed_slots += schedule.slots_between(cursor, schedule.buckets);
                break;
            }
        }
        cursor += 1;
    }

    metrics.pacer_lateness = lateness.summary();
    metrics
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
