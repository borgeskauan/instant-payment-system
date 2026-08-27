use loadtool_contract::event::Pacs008Start;
use loadtool_contract::generation_window::GenerationWindow;
use serde::Serialize;

const ROLLING_WINDOW_NS: i64 = 1_000_000_000;

#[derive(Clone, Debug, Default, PartialEq, Serialize)]
pub struct GenerationSummary {
    pub planned_originals: u64,
    pub executed_originals: usize,
    pub required_minimum_tps: u64,
    pub minimum_rolling_tps: usize,
    pub valid: bool,
}

pub fn summarize(
    starts: &[Pacs008Start],
    window: &GenerationWindow,
    offered_tps: u64,
    required_minimum_tps: u64,
) -> GenerationSummary {
    let mut summary = GenerationSummary {
        planned_originals: offered_tps.saturating_mul(
            u64::try_from(
                (window.generation_ended_at_ns - window.active_started_at_ns) / ROLLING_WINDOW_NS,
            )
            .unwrap_or_default(),
        ),
        required_minimum_tps,
        ..GenerationSummary::default()
    };
    let mut timestamps = Vec::with_capacity(starts.len());
    for start in starts {
        let started_at = request_started_at(start);
        if started_at >= window.active_started_at_ns && started_at < window.generation_ended_at_ns {
            timestamps.push(started_at);
        }
    }
    summary.executed_originals = timestamps.len();
    let duration_ns = window.generation_ended_at_ns - window.active_started_at_ns;
    if duration_ns < ROLLING_WINDOW_NS || timestamps.is_empty() {
        return summary;
    }

    timestamps.sort_unstable();
    summary.minimum_rolling_tps = minimum_rolling_count(
        &timestamps,
        window.active_started_at_ns,
        window.generation_ended_at_ns,
    );
    summary.valid = summary.executed_originals as u64 == summary.planned_originals
        && summary.minimum_rolling_tps >= required_minimum_tps as usize;
    summary
}

fn minimum_rolling_count(timestamps: &[i64], active_start: i64, active_end: i64) -> usize {
    let last_start = active_end - ROLLING_WINDOW_NS;
    let mut left = 0;
    let mut right = 0;
    let mut minimum = timestamps.len();
    observe(
        timestamps,
        active_start,
        &mut left,
        &mut right,
        &mut minimum,
        false,
    );
    let mut last_candidate = Some(active_start);
    for &timestamp in timestamps {
        if timestamp >= last_start {
            break;
        }
        let candidate = timestamp.saturating_add(1);
        if Some(candidate) != last_candidate {
            observe(
                timestamps,
                candidate,
                &mut left,
                &mut right,
                &mut minimum,
                false,
            );
            last_candidate = Some(candidate);
        }
    }
    if last_candidate != Some(last_start) {
        observe(
            timestamps,
            last_start,
            &mut left,
            &mut right,
            &mut minimum,
            false,
        );
    }
    minimum
}

fn observe(
    timestamps: &[i64],
    start: i64,
    left: &mut usize,
    right: &mut usize,
    result: &mut usize,
    maximum: bool,
) {
    while *left < timestamps.len() && timestamps[*left] < start {
        *left += 1;
    }
    *right = (*right).max(*left);
    let end = start.saturating_add(ROLLING_WINDOW_NS);
    while *right < timestamps.len() && timestamps[*right] < end {
        *right += 1;
    }
    let count = *right - *left;
    if maximum {
        *result = (*result).max(count);
    } else {
        *result = (*result).min(count);
    }
}

fn request_started_at(start: &Pacs008Start) -> i64 {
    if start.request_started_at_ns == 0 {
        start.created_at_ns
    } else {
        start.request_started_at_ns
    }
}
