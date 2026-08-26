use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::Path;

use anyhow::{Context, Result};
use serde::Serialize;
use time::OffsetDateTime;
use time::format_description::well_known::Rfc3339;

#[derive(Debug, Serialize)]
pub struct RunWindow {
    schema_version: u8,
    profile: Profile,
    window: Window,
}

#[derive(Debug, Serialize)]
struct Profile {
    name: String,
}

#[derive(Debug, Serialize)]
struct Window {
    generation_started_at: String,
    warmup_ended_at: String,
    active_started_at: String,
    generation_ended_at: String,
    replay_deadline_at: String,
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
