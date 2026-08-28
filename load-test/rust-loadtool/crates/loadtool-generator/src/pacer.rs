use std::collections::BTreeSet;
use std::hint::spin_loop;
use std::sync::mpsc::{Receiver, RecvTimeoutError};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use anyhow::{Result, anyhow, bail};
use tokio::sync::mpsc;

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

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct PacerResult {
    pub missed_slots: u64,
}

#[derive(Default)]
struct PacerState {
    missed_slots: u64,
    channel_closed: bool,
}

impl PacerState {
    fn miss(&mut self, count: u64) {
        self.missed_slots = self.missed_slots.saturating_add(count);
    }
}

pub fn spawn_prepared_pacer<T, Admit>(
    schedule: PhaseSchedule,
    descriptor_sender: mpsc::Sender<BucketDescriptor>,
    prepared_receiver: Receiver<PreparedBucket<T>>,
    admit: Admit,
) -> Result<JoinHandle<PacerResult>>
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
) -> PacerResult
where
    T: Send + 'static,
    Admit: FnMut(PreparedBucket<T>),
{
    let mut state = PacerState::default();
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
        &mut state,
    );

    while cursor < schedule.buckets && !state.channel_closed {
        let advance = advance_cursor(&schedule, cursor, Instant::now());
        let Some(bucket) = advance.next_bucket else {
            if next_prepare < schedule.buckets {
                state.miss(schedule.slots_between(next_prepare, schedule.buckets));
            }
            for skipped in cursor..schedule.buckets {
                if dispatched.remove(&skipped)
                    && let Some(descriptor) = schedule.descriptor(skipped)
                {
                    state.miss(descriptor.request_count);
                }
            }
            break;
        };
        if bucket > next_prepare {
            state.miss(schedule.slots_between(next_prepare, bucket));
            next_prepare = bucket;
        }
        for skipped in cursor..bucket {
            if dispatched.remove(&skipped)
                && let Some(descriptor) = schedule.descriptor(skipped)
            {
                state.miss(descriptor.request_count);
            }
        }
        cursor = bucket;

        let timing = schedule
            .descriptor(bucket)
            .expect("live bucket has a descriptor");
        wait_until(timing.bucket_start);

        if dispatched.remove(&bucket) {
            match take_prepared_until(
                bucket,
                timing.bucket_deadline,
                &prepared_receiver,
                &mut pending,
            ) {
                Some(prepared) => admit(prepared),
                None => {
                    state.miss(timing.request_count);
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
            &mut state,
        );
    }

    PacerResult {
        missed_slots: state.missed_slots,
    }
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
    state: &mut PacerState,
) {
    while *next_prepare < target && !state.channel_closed {
        let descriptor = schedule
            .descriptor(*next_prepare)
            .expect("prepared bucket has a descriptor");
        *next_prepare += 1;
        if descriptor.request_count == 0 {
            continue;
        }
        wait_until(descriptor.preparation_start);
        if Instant::now() >= descriptor.bucket_deadline {
            state.miss(descriptor.request_count);
            continue;
        }
        match sender.try_send(descriptor) {
            Ok(()) => {
                dispatched.insert(*next_prepare - 1);
            }
            Err(mpsc::error::TrySendError::Full(descriptor)) => {
                state.miss(descriptor.request_count);
            }
            Err(mpsc::error::TrySendError::Closed(_)) => {
                state.channel_closed = true;
                state.miss(schedule.slots_between(*next_prepare - 1, schedule.buckets));
            }
        }
    }
}
fn wait_until(target: Instant) {
    let now = Instant::now();
    if now >= target {
        return;
    }
    let remaining = target.duration_since(now);
    if remaining > SPIN_TAIL {
        thread::sleep(remaining - SPIN_TAIL);
    }
    while Instant::now() < target {
        spin_loop();
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
