use std::collections::HashMap;

use loadtool_contract::event::{Pacs002Start, Pacs008Start, Replay};
use loadtool_contract::model::ReplayRule;
use loadtool_contract::run_window::ResolvedWindow;
use serde::Serialize;

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct ReplaySummary {
    pub pacs008: ReplayTypeSummary,
    pub pacs002: ReplayTypeSummary,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct ReplayTypeSummary {
    pub started: usize,
    pub accepted: usize,
    pub violations: usize,
}

pub(crate) fn pacs008(
    starts: &[Pacs008Start],
    replays: &[Replay],
    configured: Option<&ReplayRule>,
    window: &ResolvedWindow,
) -> ReplayTypeSummary {
    let starts_by_id: HashMap<_, _> = starts
        .iter()
        .map(|start| (start.end_to_end_id.as_str(), start))
        .collect();
    let mut attempts = HashMap::<&str, usize>::new();
    let mut summary = ReplayTypeSummary::default();
    for replay in replays
        .iter()
        .filter(|value| value.message_type == "pacs.008")
    {
        summary.started += 1;
        if success(replay.http_status) {
            summary.accepted += 1;
        } else {
            summary.violations += 1;
        }
        let Some(start) = starts_by_id.get(replay.end_to_end_id.as_str()).copied() else {
            summary.violations += 1;
            continue;
        };
        *attempts.entry(start.end_to_end_id.as_str()).or_default() += 1;
        if !start.replay_selected
            || replay.sender_ispb != start.payer_ispb
            || replay.scenario_name != start.scenario_name
        {
            summary.violations += 1;
        }
        let Some(rule) = configured else {
            summary.violations += 1;
            continue;
        };
        if replay.request_started_at_ns < delayed(start.request_started_at_ns, rule.delay_seconds)
            || replay.request_started_at_ns >= window.replay_deadline_at_ns
        {
            summary.violations += 1;
        }
    }
    for start in starts.iter().filter(|value| value.replay_selected) {
        match attempts
            .get(start.end_to_end_id.as_str())
            .copied()
            .unwrap_or(0)
        {
            0 => summary.violations += 1,
            count if count > 1 => summary.violations += count - 1,
            _ => {}
        }
    }
    summary
}

pub(crate) fn pacs002(
    starts: &[Pacs002Start],
    replays: &[Replay],
    configured: Option<&ReplayRule>,
    window: &ResolvedWindow,
) -> ReplayTypeSummary {
    let starts_by_id: HashMap<_, _> = starts
        .iter()
        .map(|start| (start.end_to_end_id.as_str(), start))
        .collect();
    let mut attempts = HashMap::<&str, usize>::new();
    let mut summary = ReplayTypeSummary::default();
    for replay in replays
        .iter()
        .filter(|value| value.message_type == "pacs.002")
    {
        summary.started += 1;
        if success(replay.http_status) {
            summary.accepted += 1;
        } else {
            summary.violations += 1;
        }
        let Some(start) = starts_by_id.get(replay.end_to_end_id.as_str()).copied() else {
            summary.violations += 1;
            continue;
        };
        *attempts.entry(start.end_to_end_id.as_str()).or_default() += 1;
        if !start.replay_selected
            || replay.sender_ispb != start.sender_ispb
            || replay.scenario_name != start.scenario_name
        {
            summary.violations += 1;
        }
        let Some(rule) = configured else {
            summary.violations += 1;
            continue;
        };
        if replay.request_started_at_ns < delayed(start.request_started_at_ns, rule.delay_seconds)
            || replay.request_started_at_ns >= window.replay_deadline_at_ns
        {
            summary.violations += 1;
        }
    }
    for start in starts.iter().filter(|value| value.replay_selected) {
        if start.request_started_at_ns >= window.generation_ended_at_ns {
            summary.violations += 1;
        }
        match attempts
            .get(start.end_to_end_id.as_str())
            .copied()
            .unwrap_or(0)
        {
            0 => summary.violations += 1,
            count if count > 1 => summary.violations += count - 1,
            _ => {}
        }
    }
    summary
}

fn success(status: u16) -> bool {
    (200..300).contains(&status)
}

fn delayed(timestamp: i64, seconds: u64) -> i64 {
    timestamp
        .saturating_add(i64::try_from(seconds.saturating_mul(1_000_000_000)).unwrap_or(i64::MAX))
}
