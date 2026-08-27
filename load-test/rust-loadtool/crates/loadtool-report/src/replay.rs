use loadtool_contract::event::Replay;
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

pub(crate) fn summarize(
    replays: &[Replay],
    message_type: &str,
    selected: usize,
) -> ReplayTypeSummary {
    let mut summary = ReplayTypeSummary::default();
    for replay in replays
        .iter()
        .filter(|replay| replay.message_type == message_type)
    {
        summary.started += 1;
        if success(replay.http_status) {
            summary.accepted += 1;
        }
    }
    summary.violations = summary.started.abs_diff(selected) + summary.started - summary.accepted;
    summary
}

fn success(status: u16) -> bool {
    (200..300).contains(&status)
}
