use std::time::Duration;

use anyhow::{Context, Result, anyhow};
use serde::{Deserialize, Serialize, Serializer};

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
        Ok(Self {
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
        })
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
