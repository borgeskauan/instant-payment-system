use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result, anyhow};

#[derive(Clone, Copy, Debug)]
pub struct RunClock {
    monotonic_origin: Instant,
    wall_origin: SystemTime,
}

impl RunClock {
    pub fn new(monotonic_origin: Instant, wall_origin: SystemTime) -> Self {
        Self {
            monotonic_origin,
            wall_origin,
        }
    }

    pub fn monotonic_origin(&self) -> Instant {
        self.monotonic_origin
    }

    pub fn unix_nanos(&self, instant: Instant) -> Result<u64> {
        let offset = instant
            .checked_duration_since(self.monotonic_origin)
            .ok_or_else(|| anyhow!("instant is before run monotonic origin"))?;
        let projected = self
            .wall_origin
            .checked_add(offset)
            .ok_or_else(|| anyhow!("projected wall clock overflows"))?;
        let since_epoch = projected
            .duration_since(UNIX_EPOCH)
            .context("projected wall clock is before Unix epoch")?;
        u64::try_from(since_epoch.as_nanos()).context("projected Unix nanoseconds overflow u64")
    }

    pub fn unix_nanos_offset(&self, offset_ns: u64) -> Result<u64> {
        let instant = self
            .monotonic_origin
            .checked_add(Duration::from_nanos(offset_ns))
            .ok_or_else(|| anyhow!("event monotonic offset overflows Instant"))?;
        self.unix_nanos(instant)
    }
}
