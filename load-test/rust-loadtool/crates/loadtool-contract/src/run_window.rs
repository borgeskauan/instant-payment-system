use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::Path;

use anyhow::{Context, Result, anyhow};
use serde::{Deserialize, Serialize};
use time::OffsetDateTime;
use time::format_description::well_known::Rfc3339;

use crate::model::ExecutionPlan;

#[derive(Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RunWindow {
    pub schema_version: u8,
    pub profile: Profile,
    pub window: Window,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Profile {
    pub name: String,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Window {
    pub generation_started_at: String,
    pub warmup_ended_at: String,
    pub active_started_at: String,
    pub generation_ended_at: String,
    pub replay_deadline_at: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ResolvedWindow {
    pub generation_started_at_ns: i64,
    pub warmup_ended_at_ns: i64,
    pub active_started_at_ns: i64,
    pub generation_ended_at_ns: i64,
    pub replay_deadline_at_ns: i64,
}

impl RunWindow {
    pub fn new(
        profile: impl Into<String>,
        generation_started_at_ns: u64,
        warmup_ended_at_ns: u64,
        active_started_at_ns: u64,
        generation_ended_at_ns: u64,
        replay_deadline_at_ns: u64,
    ) -> Self {
        Self {
            schema_version: 2,
            profile: Profile {
                name: profile.into(),
            },
            window: Window {
                generation_started_at: timestamp(generation_started_at_ns),
                warmup_ended_at: timestamp(warmup_ended_at_ns),
                active_started_at: timestamp(active_started_at_ns),
                generation_ended_at: timestamp(generation_ended_at_ns),
                replay_deadline_at: timestamp(replay_deadline_at_ns),
            },
        }
    }

    pub fn decode(data: &[u8]) -> Result<Self> {
        serde_json::from_slice(data).context("run-window.json does not match the contract")
    }

    pub fn resolve(&self, profile_name: &str, plan: &ExecutionPlan) -> Result<ResolvedWindow> {
        if self.schema_version != 2 {
            return Err(anyhow!("run window schema_version must be 2"));
        }
        if self.profile.name != profile_name || self.profile.name != plan.profile {
            return Err(anyhow!(
                "run window profile {:?} does not match execution profile {:?}",
                self.profile.name,
                plan.profile
            ));
        }
        let resolved = ResolvedWindow {
            generation_started_at_ns: parse_timestamp(&self.window.generation_started_at)?,
            warmup_ended_at_ns: parse_timestamp(&self.window.warmup_ended_at)?,
            active_started_at_ns: parse_timestamp(&self.window.active_started_at)?,
            generation_ended_at_ns: parse_timestamp(&self.window.generation_ended_at)?,
            replay_deadline_at_ns: parse_timestamp(&self.window.replay_deadline_at)?,
        };
        let warmup_ns = duration_ns(
            plan.load
                .warmup
                .bootstrap
                .duration
                .checked_add(plan.load.warmup.steady.duration)
                .ok_or_else(|| anyhow!("warmup duration overflows"))?,
        )?;
        if resolved.warmup_ended_at_ns
            != resolved
                .generation_started_at_ns
                .checked_add(warmup_ns)
                .ok_or_else(|| anyhow!("warmup boundary overflows"))?
        {
            return Err(anyhow!(
                "run window warmup_ended_at is inconsistent with warmup"
            ));
        }
        if resolved.active_started_at_ns < resolved.warmup_ended_at_ns {
            return Err(anyhow!(
                "run window active_started_at precedes warmup_ended_at"
            ));
        }
        if resolved.generation_ended_at_ns
            != resolved
                .active_started_at_ns
                .checked_add(duration_ns(plan.load.active_duration)?)
                .ok_or_else(|| anyhow!("active boundary overflows"))?
        {
            return Err(anyhow!(
                "run window generation_ended_at is inconsistent with duration"
            ));
        }
        if resolved.replay_deadline_at_ns
            != resolved
                .generation_ended_at_ns
                .checked_add(duration_ns(plan.load.drain)?)
                .ok_or_else(|| anyhow!("replay boundary overflows"))?
        {
            return Err(anyhow!(
                "run window replay_deadline_at is inconsistent with drain"
            ));
        }
        Ok(resolved)
    }
}

pub fn write_run_window_atomic(path: &Path, window: &RunWindow) -> Result<()> {
    let parent = path.parent().context("run-window path has no parent")?;
    let temporary = parent.join(format!(
        ".{}.tmp",
        path.file_name()
            .context("run-window path has no file name")?
            .to_string_lossy()
    ));
    let mut file = OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(&temporary)
        .with_context(|| format!("create {}", temporary.display()))?;
    let result = (|| -> Result<()> {
        serde_json::to_writer_pretty(&mut file, window)?;
        file.write_all(b"\n")?;
        file.sync_all()?;
        fs::rename(&temporary, path).with_context(|| format!("publish {}", path.display()))?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

fn timestamp(unix_ns: u64) -> String {
    OffsetDateTime::from_unix_timestamp_nanos(i128::from(unix_ns))
        .expect("u64 Unix nanoseconds fit OffsetDateTime")
        .format(&Rfc3339)
        .expect("RFC3339 formatting is infallible for OffsetDateTime")
}

fn parse_timestamp(value: &str) -> Result<i64> {
    let timestamp = OffsetDateTime::parse(value, &Rfc3339)
        .with_context(|| format!("parse RFC3339 timestamp {value:?}"))?;
    i64::try_from(timestamp.unix_timestamp_nanos())
        .with_context(|| format!("timestamp {value:?} is outside i64 nanoseconds"))
}

fn duration_ns(value: std::time::Duration) -> Result<i64> {
    i64::try_from(value.as_nanos()).context("duration exceeds i64 nanoseconds")
}
