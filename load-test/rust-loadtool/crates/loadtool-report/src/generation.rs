use loadtool_contract::event::Pacs008Start;
use loadtool_contract::run_window::ResolvedWindow;
use serde::Serialize;

const ROLLING_WINDOW_NS: i64 = 1_000_000_000;

#[derive(Clone, Debug, Default, PartialEq, Serialize)]
pub struct GenerationSummary {
    pub offered_tps: u64,
    pub required_minimum_tps: u64,
    pub started: usize,
    pub rolling_window_seconds: u8,
    pub average_tps: f64,
    pub minimum_observed_tps: usize,
    pub maximum_observed_tps: usize,
    pub sustained_minimum_met: bool,
    pub outside_window: usize,
}

pub fn summarize(
    starts: &[Pacs008Start],
    window: &ResolvedWindow,
    offered_tps: u64,
    required_minimum_tps: u64,
) -> GenerationSummary {
    let mut summary = GenerationSummary {
        offered_tps,
        required_minimum_tps,
        rolling_window_seconds: 1,
        ..GenerationSummary::default()
    };
    let mut timestamps = Vec::with_capacity(starts.len());
    for start in starts {
        let started_at = request_started_at(start);
        if started_at < window.generation_started_at_ns
            || started_at >= window.generation_ended_at_ns
        {
            summary.outside_window += 1;
        }
        if started_at >= window.active_started_at_ns && started_at < window.generation_ended_at_ns {
            timestamps.push(started_at);
        }
    }
    summary.started = timestamps.len();
    let duration_ns = window.generation_ended_at_ns - window.active_started_at_ns;
    if duration_ns > 0 {
        summary.average_tps =
            round_three(summary.started as f64 / (duration_ns as f64 / ROLLING_WINDOW_NS as f64));
    }
    if duration_ns < ROLLING_WINDOW_NS || timestamps.is_empty() {
        return summary;
    }

    timestamps.sort_unstable();
    summary.minimum_observed_tps = minimum_rolling_count(
        &timestamps,
        window.active_started_at_ns,
        window.generation_ended_at_ns,
    );
    summary.maximum_observed_tps = maximum_rolling_count(
        &timestamps,
        window.active_started_at_ns,
        window.generation_ended_at_ns,
    );
    summary.sustained_minimum_met = summary.minimum_observed_tps >= required_minimum_tps as usize;
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

fn maximum_rolling_count(timestamps: &[i64], active_start: i64, active_end: i64) -> usize {
    let last_start = active_end - ROLLING_WINDOW_NS;
    let mut left = 0;
    let mut right = 0;
    let mut maximum = 0;
    observe(
        timestamps,
        active_start,
        &mut left,
        &mut right,
        &mut maximum,
        true,
    );
    let mut last_candidate = Some(active_start);
    for &timestamp in timestamps {
        if timestamp > last_start {
            break;
        }
        if Some(timestamp) != last_candidate {
            observe(
                timestamps,
                timestamp,
                &mut left,
                &mut right,
                &mut maximum,
                true,
            );
            last_candidate = Some(timestamp);
        }
    }
    if last_candidate != Some(last_start) {
        observe(
            timestamps,
            last_start,
            &mut left,
            &mut right,
            &mut maximum,
            true,
        );
    }
    maximum
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

fn round_three(value: f64) -> f64 {
    (value * 1000.0).round() / 1000.0
}
