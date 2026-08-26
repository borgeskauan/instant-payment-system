use std::sync::Arc;

use anyhow::{Result, anyhow, bail};

use crate::replay::{ReplayDomain, stable_rotation};
use loadtool_contract::model::{ExecutionPlan, Scenario};

const BLOCK_SIZE: u64 = 100;

pub fn requests_in_bucket(rate: u64, bucket: u64) -> u64 {
    let current = u128::from(bucket) * u128::from(rate) / 1000;
    let next = u128::from(bucket + 1) * u128::from(rate) / 1000;
    u64::try_from(next - current).expect("one bucket count fits in u64")
}

#[derive(Clone, Copy, Debug)]
struct Assignment {
    scenario_index: usize,
    local_ordinal: u64,
    pacs002_ordinal: Option<u64>,
}

#[derive(Debug)]
pub struct Planner {
    plan: Arc<ExecutionPlan>,
    quotas: Vec<u64>,
    pacs002_per_block: u64,
    layouts: Vec<Vec<Assignment>>,
}

#[derive(Clone, Copy, Debug)]
pub struct PlannedPayment<'a> {
    pub sequence: u64,
    pub scenario_index: usize,
    pub scenario_name: &'a str,
    pub scenario_ordinal: u64,
    pub pair_number: u32,
    pub amount_cents: i64,
    pub pacs002_ordinal: Option<u64>,
}

impl Planner {
    pub fn new(plan: Arc<ExecutionPlan>) -> Result<Self> {
        if plan.scenarios.is_empty() {
            bail!("execution plan needs at least one scenario");
        }
        let mut quotas = Vec::with_capacity(plan.scenarios.len());
        let mut total = 0u64;
        for scenario in &plan.scenarios {
            let exact = scenario.share * BLOCK_SIZE as f64;
            let quota = exact.round();
            if !(scenario.share > 0.0 && (exact - quota).abs() <= f64::EPSILON * BLOCK_SIZE as f64)
            {
                bail!("scenario {} has an inexact block share", scenario.name);
            }
            let quota = quota as u64;
            total = total
                .checked_add(quota)
                .ok_or_else(|| anyhow!("scenario quota overflows"))?;
            quotas.push(quota);
            validate_scenario(scenario)?;
        }
        if total != BLOCK_SIZE {
            bail!("scenario shares must fill a 100-payment block");
        }

        let mut layouts = Vec::with_capacity(BLOCK_SIZE as usize);
        let pacs002_per_block = plan
            .scenarios
            .iter()
            .zip(&quotas)
            .filter(|(scenario, _)| scenario_produces_pacs002(scenario))
            .map(|(_, quota)| quota)
            .sum();
        for rotation in 0..BLOCK_SIZE {
            layouts.push(build_layout(&plan, &quotas, rotation));
        }
        Ok(Self {
            plan,
            quotas,
            pacs002_per_block,
            layouts,
        })
    }

    pub fn payment(&self, sequence: u64) -> Result<PlannedPayment<'_>> {
        let block = sequence / BLOCK_SIZE;
        let position = sequence % BLOCK_SIZE;
        let rotation = stable_rotation(ReplayDomain::Scenario, block) as usize;
        let assignment = self.layouts[rotation][position as usize];
        let scenario = &self.plan.scenarios[assignment.scenario_index];
        let scenario_ordinal = block
            .checked_mul(self.quotas[assignment.scenario_index])
            .and_then(|value| value.checked_add(assignment.local_ordinal))
            .ok_or_else(|| anyhow!("scenario ordinal overflows"))?;
        let participants = &scenario.participants;
        let cold_every = ((1.0 / (1.0 - participants.hot_traffic_share)) as u64).max(2);
        let pair_offset = if scenario_ordinal % cold_every == 0 {
            participants.hot_pair_count
                + u32::try_from(scenario_ordinal % u64::from(participants.cold_pair_count))?
        } else {
            u32::try_from(scenario_ordinal % u64::from(participants.hot_pair_count))?
        };
        let amount_count = u64::try_from(scenario.amount.maximum - scenario.amount.minimum + 1)?;
        let amount_cents = scenario.amount.minimum
            + i64::try_from(scenario_ordinal % amount_count)
                .expect("amount offset is bounded by i64 range");
        let pacs002_ordinal = assignment.pacs002_ordinal.map(|local| {
            block
                .checked_mul(self.pacs002_per_block)
                .and_then(|value| value.checked_add(local))
                .expect("validated payment sequence keeps PACS.002 ordinal in range")
        });
        Ok(PlannedPayment {
            sequence,
            scenario_index: assignment.scenario_index,
            scenario_name: &scenario.name,
            scenario_ordinal,
            pair_number: participants.pair_number_start + pair_offset,
            amount_cents,
            pacs002_ordinal,
        })
    }
}

fn build_layout(plan: &ExecutionPlan, quotas: &[u64], rotation: u64) -> Vec<Assignment> {
    let mut scenario_counts = vec![0u64; plan.scenarios.len()];
    let mut pacs002_count = 0u64;
    (0..BLOCK_SIZE)
        .map(|position| {
            let rank = (position * 37 + rotation) % BLOCK_SIZE;
            let mut upper = 0u64;
            let scenario_index = quotas
                .iter()
                .position(|quota| {
                    upper += quota;
                    rank < upper
                })
                .expect("validated quotas cover the block");
            let local_ordinal = scenario_counts[scenario_index];
            scenario_counts[scenario_index] += 1;
            let pacs002_ordinal = if scenario_produces_pacs002(&plan.scenarios[scenario_index]) {
                let ordinal = pacs002_count;
                pacs002_count += 1;
                Some(ordinal)
            } else {
                None
            };
            Assignment {
                scenario_index,
                local_ordinal,
                pacs002_ordinal,
            }
        })
        .collect()
}

fn validate_scenario(scenario: &Scenario) -> Result<()> {
    if scenario.participants.hot_pair_count == 0 || scenario.participants.cold_pair_count == 0 {
        bail!("scenario {} needs hot and cold pairs", scenario.name);
    }
    if !(scenario.participants.hot_traffic_share > 0.0
        && scenario.participants.hot_traffic_share < 1.0)
    {
        bail!("scenario {} has invalid hot traffic share", scenario.name);
    }
    if scenario.amount.minimum <= 0 || scenario.amount.maximum < scenario.amount.minimum {
        bail!("scenario {} has invalid amount range", scenario.name);
    }
    Ok(())
}

fn scenario_produces_pacs002(scenario: &Scenario) -> bool {
    scenario.expectations.payer_notification.status == "ACSC"
}

#[derive(Clone, Debug)]
pub struct RunIdentity {
    prefix: String,
    id_prefix: String,
}

impl RunIdentity {
    pub fn new(prefix: impl Into<String>) -> Self {
        let prefix = prefix.into();
        let id_prefix = format!("{prefix}-");
        Self { prefix, id_prefix }
    }

    pub fn prefix(&self) -> &str {
        &self.prefix
    }

    pub fn end_to_end_id(&self, sequence: u64) -> String {
        format!("{}{sequence}", self.id_prefix)
    }

    pub fn sequence(&self, end_to_end_id: &str) -> Option<u64> {
        end_to_end_id
            .strip_prefix(&self.id_prefix)
            .and_then(|value| value.parse().ok())
    }
}
