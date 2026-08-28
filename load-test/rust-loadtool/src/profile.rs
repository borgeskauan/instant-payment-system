use std::collections::HashSet;
use std::fs;
use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, Result, anyhow, bail};
use loadtool_contract::model::{
    AmountRange, ExecutionPlan, Expectations, Funding, FundingAccount, LoadPlan, Participants,
    PayerNotification, Provisioning, ReplayPlan, ReplayRule, Scenario, WarmupPlan, WarmupStage,
    contract_name_is_valid, percentage_quota,
};
use serde::Deserialize;

const MAX_PAIR_NUMBER: u32 = 999_999;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct SourceProfile {
    name: String,
    connections: SourceConnections,
    load: SourceLoad,
    #[serde(default)]
    replay: SourceReplay,
    scenarios: Vec<SourceScenario>,
    reporting: SourceReporting,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct SourceConnections {
    central_transfer: SourceCentralTransfer,
    notification_gateway: SourceGateway,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct SourceCentralTransfer {
    base_url: String,
    ca_cert: String,
    client_cert_root: String,
    server_name: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct SourceGateway {
    address: String,
    ca_cert: String,
    client_cert_root: String,
    server_name: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct SourceLoad {
    offered_tx_rate: u64,
    required_minimum_tx_rate: u64,
    warmup: SourceWarmup,
    duration: String,
    drain: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct SourceWarmup {
    bootstrap: SourceWarmupStage,
    steady: SourceWarmupStage,
    completion_timeout: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct SourceWarmupStage {
    offered_tx_rate: u64,
    duration: String,
    request_timeout: String,
}

#[derive(Default, Deserialize)]
#[serde(deny_unknown_fields)]
struct SourceReplay {
    pacs008: Option<SourceReplayRule>,
    pacs002: Option<SourceReplayRule>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct SourceReplayRule {
    share: f64,
    delay: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct SourceScenario {
    name: String,
    share: f64,
    participants: SourceParticipants,
    amount: AmountRange,
    funding: SourceFunding,
    expectations: Expectations,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct SourceParticipants {
    hot_pair_count: u32,
    cold_pair_count: u32,
    hot_traffic_share: f64,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct SourceFunding {
    payer: FundingAccount,
    receiver: FundingAccount,
    reset_if_exists: Option<bool>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct SourceReporting {
    sla_threshold_ms: i64,
}

pub fn compile(profiles_dir: &Path, name: &str) -> Result<ExecutionPlan> {
    validate_name(name)?;
    let path = profiles_dir.join(format!("{name}.json"));
    let data = match fs::read(&path) {
        Ok(data) => data,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            bail!("profile {name:?} not found")
        }
        Err(error) => return Err(error).with_context(|| format!("read profile {name:?}")),
    };
    let source: SourceProfile = serde_json::from_slice(&data)
        .with_context(|| format!("profile {name:?} is malformed: invalid JSON contract"))?;
    build(name, source)
}

pub fn validate_name(name: &str) -> Result<()> {
    if !contract_name_is_valid(name) {
        bail!(
            "invalid profile name {name:?}: use only lowercase letters, digits, and hyphens, beginning with a letter or digit"
        );
    }
    Ok(())
}

fn build(name: &str, source: SourceProfile) -> Result<ExecutionPlan> {
    validate_name(&source.name).with_context(|| malformed(name, "name"))?;
    if source.name != name {
        bail!(
            "profile {name:?} is malformed: invalid name: name {:?} does not match selected profile {name:?}",
            source.name
        );
    }
    validate_connections(name, &source.connections)?;
    if source.load.offered_tx_rate == 0 {
        return Err(invalid(name, "load.offeredTxRate", "must be positive"));
    }
    if source.load.required_minimum_tx_rate == 0 {
        return Err(invalid(
            name,
            "load.requiredMinimumTxRate",
            "must be positive",
        ));
    }
    if source.load.required_minimum_tx_rate > source.load.offered_tx_rate {
        return Err(invalid(
            name,
            "load.requiredMinimumTxRate",
            "must not exceed load.offeredTxRate",
        ));
    }
    let warmup = WarmupPlan {
        bootstrap: stage(name, "load.warmup.bootstrap", source.load.warmup.bootstrap)?,
        steady: stage(name, "load.warmup.steady", source.load.warmup.steady)?,
        completion_timeout: parse_duration(
            name,
            "load.warmup.completionTimeout",
            &source.load.warmup.completion_timeout,
            false,
        )?,
    };
    warmup
        .bootstrap
        .duration
        .checked_add(warmup.steady.duration)
        .ok_or_else(|| invalid(name, "load.warmup", "combined duration is too large"))?;
    let active_duration = parse_duration(name, "load.duration", &source.load.duration, false)?;
    let drain = parse_duration(name, "load.drain", &source.load.drain, true)?;
    let replay = ReplayPlan {
        pacs008: replay_rule(name, "replay.pacs008", source.replay.pacs008)?,
        pacs002: replay_rule(name, "replay.pacs002", source.replay.pacs002)?,
    };
    let maximum_delay = replay
        .pacs008
        .iter()
        .chain(replay.pacs002.iter())
        .map(|rule| rule.delay_seconds)
        .max()
        .unwrap_or_default();
    if drain.as_secs() < maximum_delay {
        return Err(invalid(
            name,
            "load.drain",
            "must be at least the largest replay delay",
        ));
    }
    if source.scenarios.is_empty() {
        return Err(invalid(
            name,
            "scenarios",
            "must contain at least one scenario",
        ));
    }
    let mut scenarios = Vec::with_capacity(source.scenarios.len());
    let mut names = HashSet::new();
    let mut quota = 0u64;
    let mut next_pair = 1u32;
    for (index, source) in source.scenarios.into_iter().enumerate() {
        let scenario = scenario(name, index, source, next_pair)?;
        if !names.insert(scenario.name.clone()) {
            return Err(invalid(
                name,
                &format!("scenarios[{index}].name"),
                "scenario names must be unique",
            ));
        }
        quota = quota
            .checked_add(percentage_quota(scenario.share).expect("scenario share was validated"))
            .ok_or_else(|| invalid(name, "scenarios.share", "quota overflows"))?;
        let pairs = scenario
            .participants
            .hot_pair_count
            .checked_add(scenario.participants.cold_pair_count)
            .ok_or_else(|| invalid(name, "scenarios.participants", "pair count overflows"))?;
        next_pair = next_pair.checked_add(pairs).ok_or_else(|| {
            invalid(
                name,
                "scenarios.participants",
                "allocated pair range overflows",
            )
        })?;
        scenarios.push(scenario);
    }
    if quota != 100 {
        return Err(invalid(name, "scenarios.share", "shares must sum to 1.0"));
    }
    if source.reporting.sla_threshold_ms <= 0 {
        return Err(invalid(
            name,
            "reporting.slaThresholdMs",
            "must be positive",
        ));
    }

    let mut plan = ExecutionPlan {
        profile: source.name,
        load: LoadPlan {
            offered_tx_rate: source.load.offered_tx_rate,
            required_minimum_tx_rate: source.load.required_minimum_tx_rate,
            warmup,
            active_duration,
            drain,
        },
        replay,
        scenarios,
    };
    let provisioning = loadtool_generator::planner::derive_provisioning(Arc::new(plan.clone()))?;
    for (scenario, provisioning) in plan.scenarios.iter_mut().zip(provisioning) {
        scenario.provisioning = provisioning;
    }
    plan.validate()
        .with_context(|| format!("profile {name:?} produced an invalid execution plan"))?;
    Ok(plan)
}

fn validate_connections(name: &str, connections: &SourceConnections) -> Result<()> {
    for (field, value) in [
        (
            "connections.centralTransfer.baseUrl",
            connections.central_transfer.base_url.as_str(),
        ),
        (
            "connections.centralTransfer.caCert",
            connections.central_transfer.ca_cert.as_str(),
        ),
        (
            "connections.centralTransfer.clientCertRoot",
            connections.central_transfer.client_cert_root.as_str(),
        ),
        (
            "connections.centralTransfer.serverName",
            connections.central_transfer.server_name.as_str(),
        ),
        (
            "connections.notificationGateway.address",
            connections.notification_gateway.address.as_str(),
        ),
        (
            "connections.notificationGateway.caCert",
            connections.notification_gateway.ca_cert.as_str(),
        ),
        (
            "connections.notificationGateway.clientCertRoot",
            connections.notification_gateway.client_cert_root.as_str(),
        ),
        (
            "connections.notificationGateway.serverName",
            connections.notification_gateway.server_name.as_str(),
        ),
    ] {
        if value.is_empty() {
            return Err(invalid(name, field, "must be a non-empty string"));
        }
    }
    Ok(())
}

fn stage(name: &str, prefix: &str, source: SourceWarmupStage) -> Result<WarmupStage> {
    if source.offered_tx_rate == 0 {
        return Err(invalid(
            name,
            &format!("{prefix}.offeredTxRate"),
            "must be positive",
        ));
    }
    Ok(WarmupStage {
        offered_tx_rate: source.offered_tx_rate,
        duration: parse_duration(name, &format!("{prefix}.duration"), &source.duration, false)?,
        request_timeout: parse_duration(
            name,
            &format!("{prefix}.requestTimeout"),
            &source.request_timeout,
            false,
        )?,
    })
}

fn replay_rule(
    name: &str,
    field: &str,
    source: Option<SourceReplayRule>,
) -> Result<Option<ReplayRule>> {
    source
        .map(|source| {
            if source.share <= 0.0 || source.share > 1.0 || percentage_quota(source.share).is_none() {
                return Err(invalid(
                    name,
                    &format!("{field}.share"),
                    "must be greater than 0, at most 1, and select a whole number of entries in a 100-entry block",
                ));
            }
            Ok(ReplayRule {
                share: source.share,
                delay_seconds: parse_duration(
                    name,
                    &format!("{field}.delay"),
                    &source.delay,
                    false,
                )?
                .as_secs(),
            })
        })
        .transpose()
}

fn scenario(
    profile: &str,
    index: usize,
    source: SourceScenario,
    pair_number_start: u32,
) -> Result<Scenario> {
    let prefix = format!("scenarios[{index}]");
    validate_name(&source.name).map_err(|_| {
        invalid(
            profile,
            &format!("{prefix}.name"),
            "scenario name must follow the profile-name contract",
        )
    })?;
    if source.share <= 0.0 || percentage_quota(source.share).is_none() {
        return Err(invalid(
            profile,
            &format!("{prefix}.share"),
            "must be positive and select a whole number of entries in a 100-entry block",
        ));
    }
    let participants = source.participants;
    if participants.hot_pair_count == 0 || participants.cold_pair_count == 0 {
        return Err(invalid(
            profile,
            &format!("{prefix}.participants"),
            "hotPairCount and coldPairCount must be positive",
        ));
    }
    if !(participants.hot_traffic_share > 0.0 && participants.hot_traffic_share < 1.0)
        || percentage_quota(participants.hot_traffic_share).is_none()
    {
        return Err(invalid(
            profile,
            &format!("{prefix}.participants.hotTrafficShare"),
            "must be greater than 0, less than 1, and expressible in whole percentage points",
        ));
    }
    let pair_count = participants
        .hot_pair_count
        .checked_add(participants.cold_pair_count)
        .ok_or_else(|| invalid(profile, "scenarios.participants", "pair count overflows"))?;
    if pair_number_start > MAX_PAIR_NUMBER || pair_count > MAX_PAIR_NUMBER - pair_number_start + 1 {
        return Err(invalid(
            profile,
            "scenarios.participants",
            "allocated pair range exceeds the maximum pair number 999999",
        ));
    }
    if source.amount.minimum <= 0 || source.amount.maximum < source.amount.minimum {
        return Err(invalid(
            profile,
            &format!("{prefix}.amount"),
            "requires a positive ordered range",
        ));
    }
    let funding = funding(profile, &prefix, source.funding)?;
    validate_expectations(profile, &prefix, &source.expectations)?;
    Ok(Scenario {
        name: source.name,
        share: source.share,
        participants: Participants {
            pair_number_start,
            hot_pair_count: participants.hot_pair_count,
            cold_pair_count: participants.cold_pair_count,
            hot_traffic_share: participants.hot_traffic_share,
        },
        amount: source.amount,
        funding: funding.clone(),
        provisioning: Provisioning {
            payer_balance: funding
                .payer
                .balance
                .clone()
                .unwrap_or_else(|| "0.00".to_owned()),
            receiver_balance: funding.receiver.balance.clone().unwrap_or_default(),
            reset_if_exists: funding.reset_if_exists,
        },
        expectations: source.expectations,
    })
}

fn funding(profile: &str, prefix: &str, source: SourceFunding) -> Result<Funding> {
    let payer = funding_account(
        profile,
        &format!("{prefix}.funding.payer"),
        source.payer,
        true,
    )?;
    let receiver = funding_account(
        profile,
        &format!("{prefix}.funding.receiver"),
        source.receiver,
        false,
    )?;
    Ok(Funding {
        payer,
        receiver,
        reset_if_exists: source.reset_if_exists.ok_or_else(|| {
            invalid(
                profile,
                &format!("{prefix}.funding.resetIfExists"),
                "must be specified",
            )
        })?,
    })
}

fn funding_account(
    profile: &str,
    field: &str,
    mut account: FundingAccount,
    allow_cover: bool,
) -> Result<FundingAccount> {
    match account.mode.as_str() {
        "fixed" => {
            let balance = account
                .balance
                .as_deref()
                .ok_or_else(|| invalid(profile, field, "fixed funding requires balance"))?;
            account.balance = Some(parse_balance(balance).map_err(|error| {
                invalid(profile, &format!("{field}.balance"), &error.to_string())
            })?);
        }
        "cover-generated-debits" if allow_cover => {
            if account.balance.is_some() {
                return Err(invalid(
                    profile,
                    &format!("{field}.balance"),
                    "must be omitted for cover-generated-debits funding",
                ));
            }
        }
        "cover-generated-debits" => {
            return Err(invalid(profile, &format!("{field}.mode"), "must be fixed"));
        }
        _ => {
            return Err(invalid(
                profile,
                &format!("{field}.mode"),
                "unsupported funding mode",
            ));
        }
    }
    Ok(account)
}

fn validate_expectations(profile: &str, prefix: &str, expectations: &Expectations) -> Result<()> {
    if expectations.http_status != "2xx" {
        return Err(invalid(
            profile,
            &format!("{prefix}.expectations.httpStatus"),
            "must be 2xx",
        ));
    }
    let notification: &PayerNotification = &expectations.payer_notification;
    if notification.delivery_semantics != "at-least-once" {
        return Err(invalid(
            profile,
            &format!("{prefix}.expectations.payerNotification.deliverySemantics"),
            "must be at-least-once",
        ));
    }
    if !pacs_code(&notification.status) {
        return Err(invalid(
            profile,
            &format!("{prefix}.expectations.payerNotification.status"),
            "must be a four-character uppercase alphanumeric PACS status code",
        ));
    }
    let mut reasons = HashSet::new();
    for reason in &notification.reason_codes {
        if !pacs_code(reason) || !reasons.insert(reason) {
            return Err(invalid(
                profile,
                &format!("{prefix}.expectations.payerNotification.reasonCodes"),
                "reason codes must be unique four-character uppercase alphanumeric values",
            ));
        }
    }
    Ok(())
}

fn parse_balance(value: &str) -> Result<String> {
    let mut parts = value.split('.');
    let whole = parts.next().unwrap_or_default();
    let fraction = parts.next();
    if parts.next().is_some()
        || whole.is_empty()
        || !whole.bytes().all(|value| value.is_ascii_digit())
        || fraction.is_some_and(|value| {
            value.is_empty()
                || value.len() > 2
                || !value.bytes().all(|digit| digit.is_ascii_digit())
        })
    {
        bail!("must be a non-negative decimal string with at most two fractional digits");
    }
    let fraction = format!("{:<02}", fraction.unwrap_or_default()).replace(' ', "0");
    let combined = format!("{whole}{fraction}");
    let cents: i64 = combined
        .parse()
        .context("overflows the supported balance range")?;
    Ok(format!("{}.{:02}", cents / 100, cents % 100))
}

fn parse_duration(name: &str, field: &str, value: &str, allow_zero: bool) -> Result<Duration> {
    let seconds = duration_seconds(value)
        .with_context(|| format!("profile {name:?} is malformed: invalid {field}"))?;
    if (!allow_zero && seconds == 0) || (allow_zero && value.starts_with('-')) {
        return Err(invalid(
            name,
            field,
            if allow_zero {
                "must not be negative"
            } else {
                "must be positive"
            },
        ));
    }
    Ok(Duration::from_secs(seconds))
}

fn duration_seconds(value: &str) -> Result<u64> {
    if value.is_empty() || value.starts_with('-') || value.starts_with('+') {
        bail!("invalid duration {value:?}");
    }
    let bytes = value.as_bytes();
    let mut cursor = 0;
    let mut total = 0.0f64;
    while cursor < bytes.len() {
        let number_start = cursor;
        while cursor < bytes.len() && (bytes[cursor].is_ascii_digit() || bytes[cursor] == b'.') {
            cursor += 1;
        }
        if number_start == cursor {
            bail!("invalid duration {value:?}");
        }
        let number: f64 = value[number_start..cursor].parse()?;
        let unit_start = cursor;
        while cursor < bytes.len() && !bytes[cursor].is_ascii_digit() && bytes[cursor] != b'.' {
            cursor += 1;
        }
        let factor = match &value[unit_start..cursor] {
            "h" => 3600.0,
            "m" => 60.0,
            "s" => 1.0,
            "ms" => 0.001,
            "us" | "µs" | "μs" => 0.000_001,
            "ns" => 0.000_000_001,
            _ => bail!("invalid duration unit in {value:?}"),
        };
        total += number * factor;
    }
    if !total.is_finite() || total < 0.0 || total.fract() != 0.0 || total > u64::MAX as f64 {
        bail!("duration must resolve to a whole number of seconds");
    }
    Ok(total as u64)
}

fn pacs_code(value: &str) -> bool {
    value.len() == 4
        && value
            .bytes()
            .all(|character| character.is_ascii_uppercase() || character.is_ascii_digit())
}

fn malformed(name: &str, field: &str) -> String {
    format!("profile {name:?} is malformed: invalid {field}")
}

fn invalid(name: &str, field: &str, reason: &str) -> anyhow::Error {
    anyhow!("profile {name:?} is malformed: invalid {field}: {reason}")
}
