use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::time::{Duration, Instant};

use anyhow::{Context, Result};
use time::OffsetDateTime;
use time::format_description::well_known::Rfc3339;

use crate::clock::RunClock;
use crate::runtime::Runtime;
use loadtool_contract::model::ExecutionPlan;

#[derive(Clone, Copy, Debug)]
pub(crate) struct WarmupBoundaries {
    pub(crate) hard_deadline: Instant,
}

impl WarmupBoundaries {
    pub(crate) fn new(start: Instant, plan: &ExecutionPlan) -> Result<Self> {
        let planned_end = start
            .checked_add(plan.load.warmup.bootstrap.duration)
            .and_then(|value| value.checked_add(plan.load.warmup.steady.duration))
            .context("warmup deadline overflows Instant")?;
        let hard_deadline = planned_end
            .checked_add(plan.load.warmup.completion_timeout)
            .context("warmup hard deadline overflows Instant")?;
        Ok(Self { hard_deadline })
    }
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct ActiveBoundaries {
    pub(crate) generation_end: Instant,
    pub(crate) hard_deadline: Instant,
}

impl ActiveBoundaries {
    pub(crate) fn new(start: Instant, plan: &ExecutionPlan) -> Result<Self> {
        let generation_end = start
            .checked_add(plan.load.active_duration)
            .context("active generation deadline overflows Instant")?;
        let hard_deadline = generation_end
            .checked_add(plan.load.drain)
            .context("active hard deadline overflows Instant")?;
        Ok(Self {
            generation_end,
            hard_deadline,
        })
    }
}

pub(crate) async fn finish_active(runtime: &Arc<Runtime>, hard_deadline: Instant) {
    tokio::select! {
        _ = tokio::time::sleep_until(hard_deadline.into()) => {}
        _ = runtime.cancellation.cancelled() => {}
    }
    runtime.accepting_work.store(false, Ordering::Release);
    runtime.cancellation.cancel();
    runtime.tasks.close();
    runtime.tasks.wait().await;
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

pub(crate) fn rfc3339_now() -> String {
    OffsetDateTime::now_utc()
        .format(&Rfc3339)
        .expect("UTC time is always RFC3339 representable")
}

pub(crate) fn offset_ns(clock: RunClock, instant: Instant) -> u64 {
    nanos(
        instant
            .checked_duration_since(clock.monotonic_origin())
            .unwrap_or_default(),
    )
}

fn nanos(duration: Duration) -> u64 {
    u64::try_from(duration.as_nanos()).unwrap_or(u64::MAX)
}
