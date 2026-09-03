use std::collections::HashSet;
use std::time::Duration;

use anyhow::{Context, Result, anyhow};
use serde::{Deserialize, Serialize, Serializer};

const PERCENTAGE_BLOCK_SIZE: u64 = 100;
const MAX_PAIR_NUMBER: u32 = 999_999;

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileSnapshot {
    pub name: String,
    pub connections: Connections,
    pub reporting: Reporting,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Reporting {
    pub sla_threshold_ms: i64,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Connections {
    pub central_transfer: CentralTransferConnection,
    pub notification_gateway: NotificationGatewayConnection,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CentralTransferConnection {
    pub base_url: String,
    pub ca_cert: String,
    pub client_cert_root: String,
    pub server_name: String,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NotificationGatewayConnection {
    pub address: String,
    pub ca_cert: String,
    pub client_cert_root: String,
    pub server_name: String,
}

#[derive(Clone, Debug)]
pub struct ExecutionPlan {
    pub profile: String,
    pub load: LoadPlan,
    pub replay: ReplayPlan,
    pub scenarios: Vec<Scenario>,
}

#[derive(Clone, Debug)]
pub struct LoadPlan {
    pub offered_tx_rate: u64,
    pub required_minimum_tx_rate: u64,
    pub warmup: WarmupPlan,
    pub active_duration: Duration,
    pub drain: Duration,
}

#[derive(Clone, Debug)]
pub struct WarmupPlan {
    pub bootstrap: WarmupStage,
    pub steady: WarmupStage,
    pub completion_timeout: Duration,
}

#[derive(Clone, Debug)]
pub struct WarmupStage {
    pub offered_tx_rate: u64,
    pub duration: Duration,
    pub request_timeout: Duration,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct ReplayPlan {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pacs008: Option<ReplayRule>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pacs002: Option<ReplayRule>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct ReplayRule {
    #[serde(serialize_with = "serialize_metric")]
    pub share: f64,
    pub delay_seconds: u64,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct Scenario {
    pub name: String,
    #[serde(serialize_with = "serialize_metric")]
    pub share: f64,
    pub participants: Participants,
    pub amount: AmountRange,
    pub funding: Funding,
    pub provisioning: Provisioning,
    pub expectations: Expectations,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct Participants {
    pub pair_number_start: u32,
    pub hot_pair_count: u32,
    pub cold_pair_count: u32,
    #[serde(serialize_with = "serialize_metric")]
    pub hot_traffic_share: f64,
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

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct AmountRange {
    pub minimum: i64,
    pub maximum: i64,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct Funding {
    pub payer: FundingAccount,
    pub receiver: FundingAccount,
    pub reset_if_exists: bool,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct FundingAccount {
    pub mode: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub balance: Option<String>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct Provisioning {
    pub payer_balance: String,
    pub receiver_balance: String,
    pub reset_if_exists: bool,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct Expectations {
    pub http_status: String,
    pub payer_notification: PayerNotification,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct PayerNotification {
    pub delivery_semantics: String,
    pub status: String,
    pub reason_codes: Vec<String>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct RawExecutionPlan {
    profile: String,
    offered_tx_rate: u64,
    required_minimum_tx_rate: u64,
    warmup_bootstrap_offered_tx_rate: u64,
    warmup_bootstrap_seconds: u64,
    warmup_bootstrap_request_timeout_seconds: u64,
    warmup_steady_offered_tx_rate: u64,
    warmup_steady_seconds: u64,
    warmup_steady_request_timeout_seconds: u64,
    warmup_seconds: u64,
    warmup_completion_timeout_seconds: u64,
    active_seconds: u64,
    drain_seconds: u64,
    replay: ReplayPlan,
    scenarios: Vec<Scenario>,
}

impl ExecutionPlan {
    pub fn decode(data: &[u8]) -> Result<Self> {
        let raw: RawExecutionPlan = serde_json::from_slice(data)
            .context("execution-plan.json does not match the normalized contract")?;
        let combined_warmup = raw
            .warmup_bootstrap_seconds
            .checked_add(raw.warmup_steady_seconds)
            .ok_or_else(|| anyhow!("warmup duration overflows"))?;
        if combined_warmup != raw.warmup_seconds {
            return Err(anyhow!(
                "warmupSeconds {} does not match bootstrap + steady {}",
                raw.warmup_seconds,
                combined_warmup
            ));
        }
        let plan = Self {
            profile: raw.profile,
            load: LoadPlan {
                offered_tx_rate: raw.offered_tx_rate,
                required_minimum_tx_rate: raw.required_minimum_tx_rate,
                warmup: WarmupPlan {
                    bootstrap: WarmupStage {
                        offered_tx_rate: raw.warmup_bootstrap_offered_tx_rate,
                        duration: Duration::from_secs(raw.warmup_bootstrap_seconds),
                        request_timeout: Duration::from_secs(
                            raw.warmup_bootstrap_request_timeout_seconds,
                        ),
                    },
                    steady: WarmupStage {
                        offered_tx_rate: raw.warmup_steady_offered_tx_rate,
                        duration: Duration::from_secs(raw.warmup_steady_seconds),
                        request_timeout: Duration::from_secs(
                            raw.warmup_steady_request_timeout_seconds,
                        ),
                    },
                    completion_timeout: Duration::from_secs(raw.warmup_completion_timeout_seconds),
                },
                active_duration: Duration::from_secs(raw.active_seconds),
                drain: Duration::from_secs(raw.drain_seconds),
            },
            replay: raw.replay,
            scenarios: raw.scenarios,
        };
        plan.validate()?;
        Ok(plan)
    }

    pub fn validate(&self) -> Result<()> {
        if !contract_name_is_valid(&self.profile) {
            return Err(anyhow!("execution plan has an invalid profile name"));
        }
        if self.load.offered_tx_rate == 0 {
            return Err(anyhow!("execution plan offeredTxRate must be positive"));
        }
        if self.load.required_minimum_tx_rate == 0
            || self.load.required_minimum_tx_rate > self.load.offered_tx_rate
        {
            return Err(anyhow!(
                "execution plan requiredMinimumTxRate must be positive and not exceed offeredTxRate"
            ));
        }
        validate_warmup_stage("bootstrap", &self.load.warmup.bootstrap)?;
        validate_warmup_stage("steady", &self.load.warmup.steady)?;
        if self.load.warmup.completion_timeout.is_zero() {
            return Err(anyhow!(
                "execution plan warmup completion timeout must be positive"
            ));
        }
        if self.load.active_duration.is_zero() {
            return Err(anyhow!("execution plan active duration must be positive"));
        }

        let mut maximum_replay_delay = 0;
        for (name, rule) in [
            ("pacs008", self.replay.pacs008.as_ref()),
            ("pacs002", self.replay.pacs002.as_ref()),
        ] {
            let Some(rule) = rule else { continue };
            if percentage_quota(rule.share).is_none() || rule.share <= 0.0 || rule.share > 1.0 {
                return Err(anyhow!(
                    "execution plan {name} replay share must be a whole percentage in (0, 1]"
                ));
            }
            if rule.delay_seconds == 0 {
                return Err(anyhow!(
                    "execution plan {name} replay delay must be positive"
                ));
            }
            maximum_replay_delay = maximum_replay_delay.max(rule.delay_seconds);
        }
        if self.load.drain.as_secs() < maximum_replay_delay {
            return Err(anyhow!(
                "execution plan drain must cover the largest replay delay"
            ));
        }

        if self.scenarios.is_empty() {
            return Err(anyhow!("execution plan requires at least one scenario"));
        }
        let mut names = HashSet::with_capacity(self.scenarios.len());
        let mut scenario_quota = 0u64;
        let mut next_pair = 1u32;
        for (index, scenario) in self.scenarios.iter().enumerate() {
            if !contract_name_is_valid(&scenario.name) || !names.insert(scenario.name.as_str()) {
                return Err(anyhow!(
                    "execution plan scenario {index} has an invalid or duplicate name"
                ));
            }
            let quota = percentage_quota(scenario.share)
                .filter(|quota| *quota > 0)
                .ok_or_else(|| {
                    anyhow!(
                        "execution plan scenario {:?} share must be a positive whole percentage",
                        scenario.name
                    )
                })?;
            scenario_quota = scenario_quota
                .checked_add(quota)
                .ok_or_else(|| anyhow!("execution plan scenario quota overflows"))?;

            let participants = &scenario.participants;
            if participants.hot_pair_count == 0 || participants.cold_pair_count == 0 {
                return Err(anyhow!(
                    "execution plan scenario {:?} needs positive hot and cold pair counts",
                    scenario.name
                ));
            }
            if participants.pair_number_start != next_pair {
                return Err(anyhow!(
                    "execution plan scenario {:?} participant range is not consecutive",
                    scenario.name
                ));
            }
            let pair_count = participants
                .hot_pair_count
                .checked_add(participants.cold_pair_count)
                .ok_or_else(|| anyhow!("execution plan participant pair count overflows"))?;
            if next_pair > MAX_PAIR_NUMBER || pair_count > MAX_PAIR_NUMBER - next_pair + 1 {
                return Err(anyhow!(
                    "execution plan participant range exceeds pair number {MAX_PAIR_NUMBER}"
                ));
            }
            next_pair = next_pair
                .checked_add(pair_count)
                .ok_or_else(|| anyhow!("execution plan participant range overflows"))?;
            if percentage_quota(participants.hot_traffic_share)
                .filter(|quota| *quota > 0 && *quota < PERCENTAGE_BLOCK_SIZE)
                .is_none()
            {
                return Err(anyhow!(
                    "execution plan scenario {:?} hotTrafficShare must be a whole percentage in (0, 1)",
                    scenario.name
                ));
            }
            if scenario.amount.minimum <= 0 || scenario.amount.maximum < scenario.amount.minimum {
                return Err(anyhow!(
                    "execution plan scenario {:?} has an invalid amount range",
                    scenario.name
                ));
            }
            validate_funding(scenario)?;
            validate_expectations(scenario)?;
            if !canonical_balance(&scenario.provisioning.payer_balance)
                || !canonical_balance(&scenario.provisioning.receiver_balance)
                || scenario.provisioning.reset_if_exists != scenario.funding.reset_if_exists
            {
                return Err(anyhow!(
                    "execution plan scenario {:?} has invalid provisioning",
                    scenario.name
                ));
            }
        }
        if scenario_quota != PERCENTAGE_BLOCK_SIZE {
            return Err(anyhow!(
                "execution plan scenario shares must fill one 100-payment block"
            ));
        }
        self.maximum_planned_slots()?;
        Ok(())
    }

    pub fn maximum_planned_slots(&self) -> Result<u64> {
        let stage_slots = |stage: &WarmupStage| {
            stage
                .offered_tx_rate
                .checked_mul(stage.duration.as_secs())
                .ok_or_else(|| anyhow!("warmup slot count overflows"))
        };
        let bootstrap = stage_slots(&self.load.warmup.bootstrap)?;
        let steady = stage_slots(&self.load.warmup.steady)?;
        let active = self
            .load
            .offered_tx_rate
            .checked_mul(self.load.active_duration.as_secs())
            .ok_or_else(|| anyhow!("active slot count overflows"))?;
        bootstrap
            .checked_add(steady)
            .and_then(|total| total.checked_add(active))
            .ok_or_else(|| anyhow!("total slot count overflows"))
    }

    pub fn encode_pretty(&self) -> Result<Vec<u8>> {
        self.validate()?;
        let raw = RawExecutionPlan {
            profile: self.profile.clone(),
            offered_tx_rate: self.load.offered_tx_rate,
            required_minimum_tx_rate: self.load.required_minimum_tx_rate,
            warmup_bootstrap_offered_tx_rate: self.load.warmup.bootstrap.offered_tx_rate,
            warmup_bootstrap_seconds: self.load.warmup.bootstrap.duration.as_secs(),
            warmup_bootstrap_request_timeout_seconds: self
                .load
                .warmup
                .bootstrap
                .request_timeout
                .as_secs(),
            warmup_steady_offered_tx_rate: self.load.warmup.steady.offered_tx_rate,
            warmup_steady_seconds: self.load.warmup.steady.duration.as_secs(),
            warmup_steady_request_timeout_seconds: self
                .load
                .warmup
                .steady
                .request_timeout
                .as_secs(),
            warmup_seconds: self
                .load
                .warmup
                .bootstrap
                .duration
                .checked_add(self.load.warmup.steady.duration)
                .context("warmup duration overflows")?
                .as_secs(),
            warmup_completion_timeout_seconds: self.load.warmup.completion_timeout.as_secs(),
            active_seconds: self.load.active_duration.as_secs(),
            drain_seconds: self.load.drain.as_secs(),
            replay: self.replay.clone(),
            scenarios: self.scenarios.clone(),
        };
        let mut encoded = serde_json::to_vec_pretty(&raw)?;
        encoded.push(b'\n');
        Ok(encoded)
    }
}

pub fn percentage_quota(share: f64) -> Option<u64> {
    let exact = share * PERCENTAGE_BLOCK_SIZE as f64;
    let rounded = exact.round();
    (share.is_finite() && (exact - rounded).abs() <= 1e-9).then_some(rounded as u64)
}

fn validate_warmup_stage(name: &str, stage: &WarmupStage) -> Result<()> {
    if stage.offered_tx_rate == 0 || stage.duration.is_zero() || stage.request_timeout.is_zero() {
        return Err(anyhow!(
            "execution plan warmup {name} rate, duration, and request timeout must be positive"
        ));
    }
    Ok(())
}

fn validate_funding(scenario: &Scenario) -> Result<()> {
    let payer_valid = match scenario.funding.payer.mode.as_str() {
        "cover-generated-debits" => scenario.funding.payer.balance.is_none(),
        "fixed" => scenario
            .funding
            .payer
            .balance
            .as_deref()
            .is_some_and(canonical_balance),
        _ => false,
    };
    let receiver_valid = scenario.funding.receiver.mode == "fixed"
        && scenario
            .funding
            .receiver
            .balance
            .as_deref()
            .is_some_and(canonical_balance);
    if !payer_valid || !receiver_valid {
        return Err(anyhow!(
            "execution plan scenario {:?} has invalid funding",
            scenario.name
        ));
    }
    Ok(())
}

fn validate_expectations(scenario: &Scenario) -> Result<()> {
    let notification = &scenario.expectations.payer_notification;
    let mut reason_codes = HashSet::with_capacity(notification.reason_codes.len());
    if scenario.expectations.http_status != "2xx"
        || notification.delivery_semantics != "at-least-once"
        || !pacs_code(&notification.status)
        || notification
            .reason_codes
            .iter()
            .any(|reason| !pacs_code(reason) || !reason_codes.insert(reason.as_str()))
    {
        return Err(anyhow!(
            "execution plan scenario {:?} has invalid expectations",
            scenario.name
        ));
    }
    Ok(())
}

pub fn contract_name_is_valid(value: &str) -> bool {
    let mut characters = value.bytes();
    characters
        .next()
        .is_some_and(|value| value.is_ascii_lowercase() || value.is_ascii_digit())
        && characters
            .all(|value| value.is_ascii_lowercase() || value.is_ascii_digit() || value == b'-')
}

fn pacs_code(value: &str) -> bool {
    value.len() == 4
        && value
            .bytes()
            .all(|character| character.is_ascii_uppercase() || character.is_ascii_digit())
}

fn canonical_balance(value: &str) -> bool {
    let Some((whole, fraction)) = value.split_once('.') else {
        return false;
    };
    !whole.is_empty()
        && (whole == "0" || !whole.starts_with('0'))
        && whole.bytes().all(|digit| digit.is_ascii_digit())
        && fraction.len() == 2
        && fraction.bytes().all(|digit| digit.is_ascii_digit())
        && format!("{whole}{fraction}").parse::<i64>().is_ok()
}
