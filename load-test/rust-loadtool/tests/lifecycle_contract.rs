use std::sync::Arc;
use std::time::{Duration, Instant};

use loadtool_generator::phase_tracker::PhaseTracker;

#[tokio::test(flavor = "current_thread")]
async fn warmup_waits_for_registered_continuations_after_generation_closes() {
    let tracker = Arc::new(PhaseTracker::new());
    tracker.add().expect("root");
    tracker.add().expect("known continuation");
    tracker.close_generation();
    tracker.done().expect("root completed");

    let early = tokio::time::timeout(
        Duration::from_millis(5),
        tracker.wait(Instant::now() + Duration::from_secs(1)),
    )
    .await;
    assert!(
        early.is_err(),
        "one pending continuation must keep the gate closed"
    );

    tracker.done().expect("continuation completed");
    tracker
        .wait(Instant::now() + Duration::from_secs(1))
        .await
        .expect("warmup gate");
    assert_eq!(tracker.pending(), 0);
}

#[tokio::test(flavor = "current_thread")]
async fn completed_gate_cannot_gain_late_work() {
    let tracker = PhaseTracker::new();
    tracker.add().unwrap();
    tracker.close_generation();
    tracker.done().unwrap();
    tracker
        .wait(Instant::now() + Duration::from_secs(1))
        .await
        .unwrap();

    let error = tracker
        .add()
        .expect_err("late continuation would reopen the gate");
    assert!(error.to_string().contains("already completed"));
}

#[tokio::test(flavor = "current_thread")]
async fn failure_or_deadline_prevents_warmup_completion() {
    let failed = PhaseTracker::new();
    failed.add().unwrap();
    failed.close_generation();
    failed.fail("contradictory warmup outcome");
    let error = failed
        .wait(Instant::now() + Duration::from_secs(1))
        .await
        .expect_err("failure must close the gate with an error");
    assert!(error.to_string().contains("contradictory warmup outcome"));

    let timed_out = PhaseTracker::new();
    timed_out.add().unwrap();
    timed_out.close_generation();
    let error = timed_out
        .wait(Instant::now() + Duration::from_millis(2))
        .await
        .expect_err("pending work must respect the absolute deadline");
    assert!(error.to_string().contains("deadline"));
}

#[test]
fn hard_deadline_is_the_minimum_of_request_timeout_and_phase_end() {
    let start = Instant::now();
    assert_eq!(
        loadtool_generator::simulator::http_deadline(
            start,
            Duration::from_secs(30),
            start + Duration::from_secs(5)
        ),
        start + Duration::from_secs(5)
    );
    assert_eq!(
        loadtool_generator::simulator::http_deadline(
            start,
            Duration::from_secs(2),
            start + Duration::from_secs(5)
        ),
        start + Duration::from_secs(2)
    );
}
