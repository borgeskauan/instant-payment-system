use std::time::Duration;

use anyhow::{Context, Result, anyhow};
use serde::Deserialize;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileSnapshot {
    pub name: String,
    pub connections: Connections,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Connections {
    pub central_transfer: CentralTransferConnection,
    pub notification_gateway: NotificationGatewayConnection,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CentralTransferConnection {
    pub base_url: String,
    pub ca_cert: String,
    pub client_cert_root: String,
    pub server_name: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NotificationGatewayConnection {
    pub address: String,
    pub ca_cert: String,
    pub client_cert_root: String,
    pub server_name: String,
}

#[derive(Debug)]
pub struct ExecutionPlan {
    pub profile: String,
    pub load: LoadPlan,
    pub replay: ReplayPlan,
    pub scenarios: Vec<Scenario>,
}

#[derive(Debug)]
pub struct LoadPlan {
    pub offered_tx_rate: u64,
    pub required_minimum_tx_rate: u64,
    pub warmup: WarmupPlan,
    pub active_duration: Duration,
    pub drain: Duration,
}

#[derive(Debug)]
pub struct WarmupPlan {
    pub bootstrap: WarmupStage,
    pub steady: WarmupStage,
    pub completion_timeout: Duration,
}

#[derive(Debug)]
pub struct WarmupStage {
    pub offered_tx_rate: u64,
    pub duration: Duration,
    pub request_timeout: Duration,
}

#[derive(Debug, Default, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct ReplayPlan {
    pub pacs008: Option<ReplayRule>,
    pub pacs002: Option<ReplayRule>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct ReplayRule {
    pub share: f64,
    pub delay_seconds: u64,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct Scenario {
    pub name: String,
    pub share: f64,
    pub participants: Participants,
    pub amount: AmountRange,
    pub funding: Funding,
    pub provisioning: Provisioning,
    pub expectations: Expectations,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct Participants {
    pub pair_number_start: u32,
    pub hot_pair_count: u32,
    pub cold_pair_count: u32,
    pub hot_traffic_share: f64,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct AmountRange {
    pub minimum: i64,
    pub maximum: i64,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct Funding {
    pub payer: FundingAccount,
    pub receiver: FundingAccount,
    pub reset_if_exists: bool,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct FundingAccount {
    pub mode: String,
    pub balance: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct Provisioning {
    pub payer_balance: String,
    pub receiver_balance: String,
    pub reset_if_exists: bool,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct Expectations {
    pub http_status: String,
    pub payer_notification: PayerNotification,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct PayerNotification {
    pub delivery_semantics: String,
    pub status: String,
    pub reason_codes: Vec<String>,
}

#[derive(Debug, Deserialize)]
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
}
