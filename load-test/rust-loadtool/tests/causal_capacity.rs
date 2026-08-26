use rust_loadtool::causal::{CausalCapacity, CausalKind};

#[test]
fn originals_and_replays_share_one_nonblocking_capacity() {
    let capacity = CausalCapacity::new(1).expect("capacity");
    let original = capacity
        .try_acquire(CausalKind::Original)
        .expect("original permit");

    let error = capacity
        .try_acquire(CausalKind::Replay)
        .expect_err("replay cannot wait behind original");
    assert!(error.to_string().contains("generator causal HTTP capacity"));
    assert_eq!(capacity.current(), 1);
    assert_eq!(capacity.maximum(), 1);

    drop(original);
    let replay = capacity
        .try_acquire(CausalKind::Replay)
        .expect("capacity is reusable after HTTP completion");
    assert_eq!(capacity.current(), 1);
    drop(replay);
    assert_eq!(capacity.current(), 0);
}

#[test]
fn replay_sleepers_do_not_consume_capacity() {
    let capacity = CausalCapacity::new(1).expect("capacity");

    assert_eq!(capacity.current(), 0);
    let permit = capacity
        .try_acquire(CausalKind::Replay)
        .expect("permit acquired only when replay starts HTTP");
    assert_eq!(capacity.current(), 1);
    drop(permit);
}

#[test]
fn zero_capacity_is_rejected_instead_of_disabling_the_bound() {
    assert!(CausalCapacity::new(0).is_err());
}
