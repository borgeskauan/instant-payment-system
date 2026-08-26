use std::collections::HashMap;
use std::mem::size_of;
use std::sync::{Arc, Barrier};
use std::thread;

use rust_loadtool::model::ExecutionPlan;
use rust_loadtool::payment_state::{OutcomeObservation, PaymentStates};
use rust_loadtool::planner::{Planner, requests_in_bucket};
use rust_loadtool::replay::{ReplayDomain, ReplaySelector, stable_rotation};

const PLAN: &str = r#"{
  "profile":"mixed",
  "offeredTxRate":2100,
  "requiredMinimumTxRate":2000,
  "warmupBootstrapOfferedTxRate":500,
  "warmupBootstrapSeconds":60,
  "warmupBootstrapRequestTimeoutSeconds":30,
  "warmupSteadyOfferedTxRate":1500,
  "warmupSteadySeconds":60,
  "warmupSteadyRequestTimeoutSeconds":5,
  "warmupSeconds":120,
  "warmupCompletionTimeoutSeconds":120,
  "activeSeconds":900,
  "drainSeconds":30,
  "replay":{"pacs008":{"share":0.05,"delaySeconds":10},"pacs002":{"share":0.05,"delaySeconds":10}},
  "scenarios":[
    {"name":"happy-path","share":0.8,"participants":{"pairNumberStart":1,"hotPairCount":8,"coldPairCount":32,"hotTrafficShare":0.8},"amount":{"minimum":100,"maximum":100098},"funding":{"payer":{"mode":"cover-generated-debits"},"receiver":{"mode":"fixed","balance":"0.00"},"resetIfExists":true},"provisioning":{"payerBalance":"1.00","receiverBalance":"0.00","resetIfExists":true},"expectations":{"httpStatus":"2xx","payerNotification":{"deliverySemantics":"at-least-once","status":"ACSC","reasonCodes":[]}}},
    {"name":"insufficient-funds","share":0.2,"participants":{"pairNumberStart":41,"hotPairCount":2,"coldPairCount":8,"hotTrafficShare":0.8},"amount":{"minimum":100,"maximum":100098},"funding":{"payer":{"mode":"fixed","balance":"0.00"},"receiver":{"mode":"fixed","balance":"0.00"},"resetIfExists":true},"provisioning":{"payerBalance":"0.00","receiverBalance":"0.00","resetIfExists":true},"expectations":{"httpStatus":"2xx","payerNotification":{"deliverySemantics":"at-least-once","status":"RJCT","reasonCodes":["AM04"]}}}
  ]
}"#;

#[test]
fn integer_bucket_distribution_is_exact() {
    let counts: Vec<u64> = (0..1000)
        .map(|bucket| requests_in_bucket(2100, bucket))
        .collect();

    assert_eq!(counts.iter().sum::<u64>(), 2100);
    assert_eq!(counts.iter().filter(|&&count| count == 2).count(), 900);
    assert_eq!(counts.iter().filter(|&&count| count == 3).count(), 100);
}

#[test]
fn stable_replay_vectors_and_quota_do_not_drift() {
    assert_eq!(
        (0..3)
            .map(|b| stable_rotation(ReplayDomain::Scenario, b))
            .collect::<Vec<_>>(),
        [20, 71, 26]
    );
    assert_eq!(
        (0..3)
            .map(|b| stable_rotation(ReplayDomain::Pacs008, b))
            .collect::<Vec<_>>(),
        [38, 33, 10]
    );
    assert_eq!(
        (0..3)
            .map(|b| stable_rotation(ReplayDomain::Pacs002, b))
            .collect::<Vec<_>>(),
        [49, 24, 40]
    );

    let pacs008 = ReplaySelector::new(0.05, ReplayDomain::Pacs008).unwrap();
    let pacs002 = ReplaySelector::new(0.05, ReplayDomain::Pacs002).unwrap();
    assert_eq!(
        (0..100)
            .filter(|&i| pacs008.selected(i))
            .collect::<Vec<_>>(),
        [18, 26, 45, 72, 99]
    );
    assert_eq!(
        (0..100)
            .filter(|&i| pacs002.selected(i))
            .collect::<Vec<_>>(),
        [15, 23, 42, 69, 96]
    );

    for invalid in [0.0, -0.1, 1.01, 0.001, 0.055] {
        assert!(ReplaySelector::new(invalid, ReplayDomain::Pacs008).is_err());
    }
}

#[test]
fn planner_preserves_scenario_populations_and_derives_dense_status_ordinals() {
    let plan = Arc::new(ExecutionPlan::decode(PLAN.as_bytes()).unwrap());
    let planner = Planner::new(plan).unwrap();
    let mut counts = HashMap::new();
    let mut status_ordinals = Vec::new();

    for sequence in 0..200 {
        let payment = planner.payment(sequence).unwrap();
        *counts.entry(payment.scenario_name).or_insert(0usize) += 1;
        if let Some(ordinal) = payment.pacs002_ordinal {
            status_ordinals.push(ordinal);
        }
        assert!((100..=100098).contains(&payment.amount_cents));
        assert!(payment.pair_number >= 1 && payment.pair_number <= 50);
    }

    assert_eq!(counts["happy-path"], 160);
    assert_eq!(counts["insufficient-funds"], 40);
    assert_eq!(status_ordinals, (0..160).collect::<Vec<_>>());
}

#[test]
fn planner_owns_its_plan_and_is_shared_across_runtime_threads() {
    let plan = Arc::new(ExecutionPlan::decode(PLAN.as_bytes()).unwrap());
    let planner = Arc::new(Planner::new(Arc::clone(&plan)).unwrap());
    let mut threads = Vec::new();

    for sequence in 0..16 {
        let planner = Arc::clone(&planner);
        threads.push(thread::spawn(move || {
            let payment = planner.payment(sequence).unwrap();
            (payment.sequence, payment.scenario_name.to_owned())
        }));
    }

    for (sequence, thread) in threads.into_iter().enumerate() {
        let payment = thread.join().unwrap();
        assert_eq!(payment.0, sequence as u64);
        assert!(!payment.1.is_empty());
    }
}

#[test]
fn payment_state_claims_once_and_accepts_at_least_once_outcomes() {
    assert_eq!(size_of::<std::sync::atomic::AtomicU8>(), 1);
    let states = Arc::new(PaymentStates::new(1));
    assert!(!states.claim_pacs002(0));
    assert!(states.commit(0));
    assert!(!states.commit(0));

    let barrier = Arc::new(Barrier::new(17));
    let mut threads = Vec::new();
    for _ in 0..16 {
        let states = Arc::clone(&states);
        let barrier = Arc::clone(&barrier);
        threads.push(thread::spawn(move || {
            barrier.wait();
            states.claim_pacs002(0)
        }));
    }
    barrier.wait();
    assert_eq!(
        threads
            .into_iter()
            .map(|handle| handle.join().unwrap())
            .filter(|claimed| *claimed)
            .count(),
        1
    );

    assert_eq!(
        states.observe_outcome(0, true),
        OutcomeObservation::MatchedFirst
    );
    assert_eq!(
        states.observe_outcome(0, true),
        OutcomeObservation::MatchedAgain
    );
    assert_eq!(
        states.observe_outcome(0, false),
        OutcomeObservation::ContradictionFirst
    );
    assert_eq!(
        states.observe_outcome(0, false),
        OutcomeObservation::ContradictionAgain
    );
}

#[test]
fn foreign_or_malformed_ids_do_not_resolve_to_a_sequence() {
    let identity = rust_loadtool::planner::RunIdentity::new("rust-123");

    assert_eq!(identity.end_to_end_id(42), "rust-123-42");
    assert_eq!(identity.sequence("rust-123-42"), Some(42));
    assert_eq!(identity.sequence("rust-124-42"), None);
    assert_eq!(identity.sequence("rust-123-nope"), None);
}
