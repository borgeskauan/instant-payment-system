use std::collections::HashMap;
use std::sync::Arc;

use anyhow::{Result, anyhow, bail};

use crate::replay::{ReplayDomain, stable_rotation};
use loadtool_contract::model::{ExecutionPlan, Provisioning, Scenario, percentage_quota};

const BLOCK_SIZE: u64 = 100;
const FUNDING_SAFETY_MULTIPLIER: i64 = 16;

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
    hot_quotas: Vec<u64>,
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
        plan.validate()?;
        let mut quotas = Vec::with_capacity(plan.scenarios.len());
        let mut hot_quotas = Vec::with_capacity(plan.scenarios.len());
        for scenario in &plan.scenarios {
            quotas.push(
                percentage_quota(scenario.share).expect("execution plan scenario was validated"),
            );
            hot_quotas.push(
                percentage_quota(scenario.participants.hot_traffic_share)
                    .expect("execution plan hot traffic share was validated"),
            );
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
            hot_quotas,
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
        let pair_offset = if selected_by_cumulative_quota(
            scenario_ordinal,
            self.hot_quotas[assignment.scenario_index],
        ) {
            u32::try_from(scenario_ordinal % u64::from(participants.hot_pair_count))?
        } else {
            participants.hot_pair_count
                + u32::try_from(scenario_ordinal % u64::from(participants.cold_pair_count))?
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

pub fn derive_provisioning(plan: Arc<ExecutionPlan>) -> Result<Vec<Provisioning>> {
    let transfer_count = plan.maximum_planned_slots()?;
    let planner = Planner::new(Arc::clone(&plan))?;
    let mut debits_by_scenario: Vec<HashMap<u32, i64>> =
        (0..plan.scenarios.len()).map(|_| HashMap::new()).collect();
    for sequence in 0..transfer_count {
        let payment = planner.payment(sequence)?;
        let scenario = &plan.scenarios[payment.scenario_index];
        if scenario.funding.payer.mode != "cover-generated-debits" {
            continue;
        }
        let debit = debits_by_scenario[payment.scenario_index]
            .entry(payment.pair_number)
            .or_default();
        *debit = debit.checked_add(payment.amount_cents).ok_or_else(|| {
            anyhow!(
                "derived debit total overflows for scenario {:?} payer pair {}",
                scenario.name,
                payment.pair_number
            )
        })?;
    }

    plan.scenarios
        .iter()
        .enumerate()
        .map(|(index, scenario)| {
            let payer_balance = match scenario.funding.payer.mode.as_str() {
                "cover-generated-debits" => {
                    let maximum = debits_by_scenario[index]
                        .values()
                        .copied()
                        .max()
                        .unwrap_or_default();
                    format_balance(maximum.checked_mul(FUNDING_SAFETY_MULTIPLIER).ok_or_else(
                        || {
                            anyhow!(
                                "derived payer funding overflows for scenario {:?}",
                                scenario.name
                            )
                        },
                    )?)
                }
                "fixed" => scenario.funding.payer.balance.clone().ok_or_else(|| {
                    anyhow!(
                        "scenario {:?} fixed payer funding requires a balance",
                        scenario.name
                    )
                })?,
                mode => bail!(
                    "scenario {:?} has unsupported payer funding mode {mode:?}",
                    scenario.name
                ),
            };
            let receiver_balance = scenario.funding.receiver.balance.clone().ok_or_else(|| {
                anyhow!(
                    "scenario {:?} requires fixed receiver funding",
                    scenario.name
                )
            })?;
            Ok(Provisioning {
                payer_balance,
                receiver_balance,
                reset_if_exists: scenario.funding.reset_if_exists,
            })
        })
        .collect()
}

fn format_balance(cents: i64) -> String {
    format!("{}.{:02}", cents / 100, cents % 100)
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

fn selected_by_cumulative_quota(ordinal: u64, quota: u64) -> bool {
    let position = ordinal % BLOCK_SIZE;
    let selected_before = position * quota / BLOCK_SIZE;
    let selected_after = (position + 1) * quota / BLOCK_SIZE;
    selected_after > selected_before
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
