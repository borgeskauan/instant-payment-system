use std::collections::HashMap;

use anyhow::{Result, anyhow, bail};
use loadtool_contract::bundle::CompletedRun;
use loadtool_contract::event::{Pacs002Start, Pacs008Start, Replay};
use loadtool_contract::generation_window::GenerationWindow;
use loadtool_contract::model::Scenario;
use serde::{Serialize, Serializer};

use crate::generation::{self, GenerationSummary};
use crate::outcome::{collect, match_payer_notifications};
use crate::replay::{self, ReplaySummary};

#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct SlaReport {
    pub valid: bool,
    pub generation: GenerationSummary,
    pub scenarios: Vec<ScenarioSummary>,
    pub replays: ReplaySummary,
    pub performance: PerformanceSummary,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct ScenarioSummary {
    pub name: String,
    #[serde(serialize_with = "serialize_metric")]
    pub share: f64,
    pub traffic: ScenarioTrafficSummary,
    pub outcome: ScenarioOutcomeSummary,
    pub performance: ScenarioPerformanceSummary,
    pub violations: usize,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize)]
pub struct ScenarioTrafficSummary {
    pub payments: CountSummary,
    pub pacs002: CountSummary,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct CountSummary {
    pub started: usize,
    pub accepted: usize,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct ScenarioOutcomeSummary {
    pub expected: ExpectedOutcomeSummary,
    pub matched: usize,
    pub missing: usize,
    pub contradictory: usize,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct ExpectedOutcomeSummary {
    pub status: String,
    pub reason_codes: Vec<String>,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize)]
pub struct ScenarioPerformanceSummary {
    pub within_threshold: usize,
    pub after_threshold: usize,
    pub latency_ms: LatencySummary,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize)]
pub struct PerformanceSummary {
    pub threshold_ms: i64,
    pub within_sla: bool,
    pub active_tps: ActiveTpsSummary,
    pub payer_notifications_after_active: usize,
    pub latency_ms: LatencySummary,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize)]
pub struct ActiveTpsSummary {
    #[serde(serialize_with = "serialize_metric")]
    pub payments: f64,
    #[serde(serialize_with = "serialize_metric")]
    pub pacs002: f64,
    #[serde(serialize_with = "serialize_metric")]
    pub pacs008_replays: f64,
    #[serde(serialize_with = "serialize_metric")]
    pub pacs002_replays: f64,
    #[serde(serialize_with = "serialize_metric")]
    pub payer_notifications: f64,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize)]
pub struct LatencySummary {
    #[serde(serialize_with = "serialize_metric")]
    pub p50: f64,
    #[serde(serialize_with = "serialize_metric")]
    pub p95: f64,
    #[serde(serialize_with = "serialize_metric")]
    pub p99: f64,
    #[serde(serialize_with = "serialize_metric")]
    pub max: f64,
}

pub fn build(run: CompletedRun) -> Result<SlaReport> {
    if run.plan.scenarios.is_empty() {
        bail!("report requires at least one configured scenario");
    }
    let scenarios = &run.plan.scenarios;
    let indexes: HashMap<_, _> = scenarios
        .iter()
        .enumerate()
        .map(|(index, scenario)| (scenario.name.as_str(), index))
        .collect();
    validate_scenarios(&run.events.pacs008, &run.events.pacs002, &indexes)?;

    let mut report = SlaReport {
        valid: false,
        generation: generation::summarize(
            &run.events.pacs008,
            &run.window,
            run.plan.load.offered_tx_rate,
            run.plan.load.required_minimum_tx_rate,
        ),
        scenarios: scenarios
            .iter()
            .map(scenario_summary)
            .collect::<Result<_>>()?,
        replays: ReplaySummary {
            pacs008: replay::summarize(
                &run.events.replays,
                "pacs.008",
                run.events
                    .pacs008
                    .iter()
                    .filter(|start| start.replay_selected)
                    .count(),
            ),
            pacs002: replay::summarize(
                &run.events.replays,
                "pacs.002",
                run.events
                    .pacs002
                    .iter()
                    .filter(|start| start.replay_selected)
                    .count(),
            ),
        },
        performance: PerformanceSummary {
            threshold_ms: run.profile.reporting.sla_threshold_ms,
            ..PerformanceSummary::default()
        },
    };
    let payer_notifications = collect(&run.events.notifications);
    for start in &run.events.pacs008 {
        let index = indexes[start.scenario_name.as_str()];
        let configured = &scenarios[index];
        let summary = &mut report.scenarios[index];
        summary.traffic.payments.started += 1;
        if !success(start.http_status) {
            summary.violations += 1;
            continue;
        }
        summary.traffic.payments.accepted += 1;
        let key = (start.end_to_end_id.clone(), start.payer_ispb.clone());
        let deliveries = payer_notifications.get(&key);
        if deliveries.is_none_or(Vec::is_empty) {
            summary.outcome.missing += 1;
            summary.violations += 1;
            continue;
        }
        let observation =
            match_payer_notifications(deliveries, &configured.expectations.payer_notification);
        if observation.matched {
            summary.outcome.matched += 1;
        }
        if observation.status_mismatch || observation.reason_codes_mismatch {
            summary.outcome.contradictory += 1;
            summary.violations += 1;
        }
    }

    for status in &run.events.pacs002 {
        let index = indexes[status.scenario_name.as_str()];
        let summary = &mut report.scenarios[index];
        summary.traffic.pacs002.started += 1;
        if success(status.http_status) {
            summary.traffic.pacs002.accepted += 1;
        } else {
            summary.violations += 1;
        }
        if status.request_started_at_ns >= run.window.replay_deadline_at_ns {
            summary.violations += 1;
        }
    }

    let measured_starts = measured_starts(&run.events.pacs008, &run.window);
    let measured_statuses = measured_statuses(&run.events.pacs002, &run.window);
    let measured_pacs008_replays = measured_replays(&run.events.replays, "pacs.008", &run.window);
    let measured_pacs002_replays = measured_replays(&run.events.replays, "pacs.002", &run.window);
    let duration_seconds = (run.window.generation_ended_at_ns - run.window.active_started_at_ns)
        as f64
        / 1_000_000_000.0;
    if duration_seconds > 0.0 {
        report.performance.active_tps.payments =
            round_three(measured_starts.len() as f64 / duration_seconds);
        report.performance.active_tps.pacs002 =
            round_three(measured_statuses.len() as f64 / duration_seconds);
        report.performance.active_tps.pacs008_replays =
            round_three(measured_pacs008_replays.len() as f64 / duration_seconds);
        report.performance.active_tps.pacs002_replays =
            round_three(measured_pacs002_replays.len() as f64 / duration_seconds);
    }

    let mut durations = Vec::new();
    let mut scenario_durations = vec![Vec::new(); scenarios.len()];
    let mut notified_during_active = 0;
    let mut matched_active = 0;
    for start in measured_starts {
        if !success(start.http_status) {
            continue;
        }
        let index = indexes[start.scenario_name.as_str()];
        let key = (start.end_to_end_id.clone(), start.payer_ispb.clone());
        let observation = match_payer_notifications(
            payer_notifications.get(&key),
            &scenarios[index].expectations.payer_notification,
        );
        if !observation.matched {
            continue;
        }
        matched_active += 1;
        let duration_ms =
            (observation.earliest_matching_at_ns - request_started_at(start)) as f64 / 1_000_000.0;
        durations.push(duration_ms);
        scenario_durations[index].push(duration_ms);
        if observation.earliest_matching_at_ns < run.window.generation_ended_at_ns {
            notified_during_active += 1;
        }
        if duration_ms > run.profile.reporting.sla_threshold_ms as f64 {
            report.scenarios[index].performance.after_threshold += 1;
        } else {
            report.scenarios[index].performance.within_threshold += 1;
        }
    }
    if duration_seconds > 0.0 {
        report.performance.active_tps.payer_notifications =
            round_three(notified_during_active as f64 / duration_seconds);
    }
    report.performance.payer_notifications_after_active = matched_active - notified_during_active;
    report.performance.latency_ms = summarize_latency(&mut durations);
    report.performance.within_sla = !durations.is_empty()
        && report.performance.latency_ms.p99 <= run.profile.reporting.sla_threshold_ms as f64;
    for (summary, values) in report
        .scenarios
        .iter_mut()
        .zip(scenario_durations.iter_mut())
    {
        summary.performance.latency_ms = summarize_latency(values);
    }
    report.valid = report.generation.valid
        && report.replays.pacs008.violations == 0
        && report.replays.pacs002.violations == 0
        && report
            .scenarios
            .iter()
            .all(|scenario| scenario.violations == 0)
        && report.performance.within_sla;
    Ok(report)
}

fn scenario_summary(scenario: &Scenario) -> Result<ScenarioSummary> {
    if scenario.expectations.http_status != "2xx" {
        bail!(
            "unsupported HTTP expectation {:?} for scenario {:?}",
            scenario.expectations.http_status,
            scenario.name
        );
    }
    if scenario.expectations.payer_notification.delivery_semantics != "at-least-once" {
        bail!(
            "unsupported payer notification delivery semantics {:?} for scenario {:?}",
            scenario.expectations.payer_notification.delivery_semantics,
            scenario.name
        );
    }
    Ok(ScenarioSummary {
        name: scenario.name.clone(),
        share: scenario.share,
        traffic: ScenarioTrafficSummary::default(),
        outcome: ScenarioOutcomeSummary {
            expected: ExpectedOutcomeSummary {
                status: scenario.expectations.payer_notification.status.clone(),
                reason_codes: scenario
                    .expectations
                    .payer_notification
                    .reason_codes
                    .clone(),
            },
            matched: 0,
            missing: 0,
            contradictory: 0,
        },
        performance: ScenarioPerformanceSummary::default(),
        violations: 0,
    })
}

fn validate_scenarios(
    starts: &[Pacs008Start],
    statuses: &[Pacs002Start],
    indexes: &HashMap<&str, usize>,
) -> Result<()> {
    for start in starts {
        if !indexes.contains_key(start.scenario_name.as_str()) {
            return Err(anyhow!(
                "start {:?} uses unknown scenario name {:?}",
                start.end_to_end_id,
                start.scenario_name
            ));
        }
    }
    for status in statuses {
        if !indexes.contains_key(status.scenario_name.as_str()) {
            return Err(anyhow!(
                "status start {:?} uses unknown scenario name {:?}",
                status.end_to_end_id,
                status.scenario_name
            ));
        }
    }
    Ok(())
}

fn measured_starts<'a>(
    starts: &'a [Pacs008Start],
    window: &GenerationWindow,
) -> Vec<&'a Pacs008Start> {
    starts
        .iter()
        .filter(|start| in_active(request_started_at(start), window))
        .collect()
}

fn measured_statuses<'a>(
    starts: &'a [Pacs002Start],
    window: &GenerationWindow,
) -> Vec<&'a Pacs002Start> {
    starts
        .iter()
        .filter(|start| in_active(start.request_started_at_ns, window))
        .collect()
}

fn measured_replays<'a>(
    replays: &'a [Replay],
    message: &str,
    window: &GenerationWindow,
) -> Vec<&'a Replay> {
    replays
        .iter()
        .filter(|replay| {
            replay.message_type == message && in_active(replay.request_started_at_ns, window)
        })
        .collect()
}

fn in_active(timestamp: i64, window: &GenerationWindow) -> bool {
    timestamp >= window.active_started_at_ns && timestamp < window.generation_ended_at_ns
}

fn request_started_at(start: &Pacs008Start) -> i64 {
    if start.request_started_at_ns == 0 {
        start.created_at_ns
    } else {
        start.request_started_at_ns
    }
}

fn success(status: u16) -> bool {
    (200..300).contains(&status)
}

fn summarize_latency(values: &mut [f64]) -> LatencySummary {
    values.sort_by(f64::total_cmp);
    LatencySummary {
        p50: round_three(percentile(values, 0.50)),
        p95: round_three(percentile(values, 0.95)),
        p99: round_three(percentile(values, 0.99)),
        max: round_three(values.last().copied().unwrap_or_default()),
    }
}

fn percentile(values: &[f64], quantile: f64) -> f64 {
    match values.len() {
        0 => 0.0,
        1 => values[0],
        _ => {
            let index = quantile * (values.len() - 1) as f64;
            let lower = index.floor() as usize;
            let upper = (lower + 1).min(values.len() - 1);
            let weight = index - lower as f64;
            values[lower] * (1.0 - weight) + values[upper] * weight
        }
    }
}

fn round_three(value: f64) -> f64 {
    (value * 1000.0).round() / 1000.0
}

fn serialize_metric<S>(value: &f64, serializer: S) -> std::result::Result<S::Ok, S::Error>
where
    S: Serializer,
{
    if value.fract() == 0.0 && *value >= i64::MIN as f64 && *value <= i64::MAX as f64 {
        serializer.serialize_i64(*value as i64)
    } else {
        serializer.serialize_f64(*value)
    }
}

#[cfg(test)]
mod tests {
    use super::{percentile, round_three};

    #[test]
    fn percentile_interpolates_and_published_values_round_to_three_decimals() {
        let values = [1.234_568, 2.0, 4.0, 8.0];
        assert_eq!(round_three(values[0]), 1.235);
        assert_eq!(percentile(&values, 0.50), 3.0);
        assert_eq!(round_three(percentile(&values, 0.95)), 7.4);
        assert_eq!(round_three(percentile(&values, 0.99)), 7.88);
    }
}
