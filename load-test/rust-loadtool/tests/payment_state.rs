use loadtool_generator::payment_state::PaymentStates;
use loadtool_generator::phase_tracker::{WarmupObservation, WarmupOutcomes};

#[test]
fn payment_state_contains_only_causal_claims() {
    let states = PaymentStates::new(2);

    assert!(!states.claim_pacs002(0));
    assert!(states.commit(0));
    assert!(states.is_committed(0));
    assert!(states.claim_pacs002(0));
    assert!(!states.claim_pacs002(0));
    assert!(!states.is_committed(1));
}

#[test]
fn outcome_tracking_is_bounded_to_the_warmup_population() {
    let outcomes = WarmupOutcomes::new(1);

    assert_eq!(
        outcomes.observe(0, true),
        Some(WarmupObservation::MatchedFirst)
    );
    assert_eq!(
        outcomes.observe(0, true),
        Some(WarmupObservation::MatchedAgain)
    );
    assert_eq!(
        outcomes.observe(0, false),
        Some(WarmupObservation::ContradictionFirst)
    );
    assert_eq!(
        outcomes.observe(0, false),
        Some(WarmupObservation::ContradictionAgain)
    );

    assert_eq!(outcomes.observe(1, true), None);
    assert_eq!(outcomes.observe(u64::MAX, false), None);
}
