use anyhow::{Context, Result, anyhow};

use crate::model::ExecutionPlan;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct GenerationWindow {
    pub generation_started_at_ns: i64,
    pub active_started_at_ns: i64,
    pub generation_ended_at_ns: i64,
    pub replay_deadline_at_ns: i64,
}

impl GenerationWindow {
    pub fn validate(&self, plan: &ExecutionPlan) -> Result<()> {
        let warmup = plan
            .load
            .warmup
            .bootstrap
            .duration
            .checked_add(plan.load.warmup.steady.duration)
            .ok_or_else(|| anyhow!("warmup duration overflows"))?;
        let earliest_active = self
            .generation_started_at_ns
            .checked_add(duration_ns(warmup)?)
            .ok_or_else(|| anyhow!("warmup boundary overflows"))?;
        if self.active_started_at_ns < earliest_active {
            return Err(anyhow!(
                "active window starts before warmup generation ends"
            ));
        }
        let expected_generation_end = self
            .active_started_at_ns
            .checked_add(duration_ns(plan.load.active_duration)?)
            .ok_or_else(|| anyhow!("active boundary overflows"))?;
        if self.generation_ended_at_ns != expected_generation_end {
            return Err(anyhow!(
                "generation end is inconsistent with active duration"
            ));
        }
        let expected_replay_deadline = self
            .generation_ended_at_ns
            .checked_add(duration_ns(plan.load.drain)?)
            .ok_or_else(|| anyhow!("replay boundary overflows"))?;
        if self.replay_deadline_at_ns != expected_replay_deadline {
            return Err(anyhow!("replay deadline is inconsistent with drain"));
        }
        Ok(())
    }
}

fn duration_ns(value: std::time::Duration) -> Result<i64> {
    i64::try_from(value.as_nanos()).context("duration exceeds i64 nanoseconds")
}
