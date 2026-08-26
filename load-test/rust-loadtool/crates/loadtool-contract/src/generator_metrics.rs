use std::collections::BTreeMap;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::Path;

use anyhow::{Context, Result};
use serde::{Serialize, Serializer};

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HistogramSummary {
    pub count: u64,
    pub p50_ns: u64,
    pub p95_ns: u64,
    pub p99_ns: u64,
    pub max_ns: u64,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SlotMetrics {
    pub planned: u64,
    pub dispatched: u64,
    pub started: u64,
    pub completed: u64,
    pub missed: u64,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PacerMisses {
    pub cursor_skip: u64,
    pub expired_before_dispatch: u64,
    pub channel_full: u64,
    pub preparation_not_ready: u64,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PacerDeadlineMisses {
    pub entered_after_deadline: u64,
    pub sleep_returned_after_deadline: u64,
    pub spin_completed_after_deadline: u64,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SemanticAdmissionMisses {
    pub before_preparation: u64,
    pub http2_readiness: u64,
    pub before_commit: u64,
}

impl SemanticAdmissionMisses {
    pub fn total(self) -> u64 {
        self.before_preparation
            .saturating_add(self.http2_readiness)
            .saturating_add(self.before_commit)
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FlowInFlight {
    pub current: u64,
    pub maximum: u64,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InFlightMetrics {
    pub original: FlowInFlight,
    pub pacs008_replay: FlowInFlight,
    pub causal_http: FlowInFlight,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProcessMetrics {
    pub user_cpu_ns: u64,
    pub system_cpu_ns: u64,
    pub maximum_rss_bytes: u64,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PullMetrics {
    pub count: u64,
    pub empty_responses: u64,
    #[serde(serialize_with = "serialize_batch_counts")]
    pub batch_size_counts: [u64; 16],
}

#[derive(Clone, Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GeneratorMetrics {
    pub valid: bool,
    pub violations: Vec<String>,
    pub slots: SlotMetrics,
    pub pacer_misses: PacerMisses,
    pub pacer_deadline_misses: PacerDeadlineMisses,
    pub semantic_admission_misses: SemanticAdmissionMisses,
    pub pacer_lateness: HistogramSummary,
    pub pacer_sleep_wake_lateness: HistogramSummary,
    pub dispatch_lateness: HistogramSummary,
    pub http_start_lateness: HistogramSummary,
    pub http_duration: HistogramSummary,
    pub late_semantic_admissions: u64,
    pub generator_capacity_violations: u64,
    pub spin_wall_time_ns: u64,
    pub in_flight: InFlightMetrics,
    pub process: ProcessMetrics,
    pub pull: PullMetrics,
}

pub fn write_generator_metrics_atomic(path: &Path, metrics: &GeneratorMetrics) -> Result<()> {
    let parent = path
        .parent()
        .context("generator metrics path has no parent")?;
    fs::create_dir_all(parent)
        .with_context(|| format!("create generator metrics directory {}", parent.display()))?;
    let temporary = parent.join(format!(
        ".{}.tmp",
        path.file_name()
            .context("generator metrics path has no file name")?
            .to_string_lossy()
    ));
    let mut file = OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(&temporary)
        .with_context(|| format!("create {}", temporary.display()))?;
    let result = (|| -> Result<()> {
        serde_json::to_writer_pretty(&mut file, metrics)?;
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

fn serialize_batch_counts<S>(
    counts: &[u64; 16],
    serializer: S,
) -> std::result::Result<S::Ok, S::Error>
where
    S: Serializer,
{
    let values: BTreeMap<String, u64> = (1..counts.len())
        .map(|size| (size.to_string(), counts[size]))
        .collect();
    values.serialize(serializer)
}
