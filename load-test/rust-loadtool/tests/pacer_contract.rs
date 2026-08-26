use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use rust_loadtool::clock::RunClock;
use rust_loadtool::pacer::{PhaseSchedule, advance_cursor, spawn_pacer};

#[test]
fn descriptors_keep_absolute_bucket_boundaries_and_sequence_positions() {
    let start = Instant::now() + Duration::from_secs(1);
    let schedule =
        PhaseSchedule::new(start, Duration::from_millis(1_000), 2_100, 17).expect("valid phase");

    let first = schedule.descriptor(0).expect("first bucket");
    assert_eq!(first.bucket_index, 0);
    assert_eq!(first.first_sequence, 17);
    assert_eq!(first.request_count, 2);
    assert_eq!(first.bucket_start, start);
    assert_eq!(first.bucket_deadline, start + Duration::from_millis(1));

    let tenth = schedule.descriptor(9).expect("tenth bucket");
    assert_eq!(tenth.first_sequence, 35);
    assert_eq!(tenth.request_count, 3);
    assert_eq!(tenth.bucket_start, start + Duration::from_millis(9));
    assert_eq!(tenth.bucket_deadline, start + Duration::from_millis(10));
}

#[test]
fn late_cursor_skips_expired_buckets_without_moving_their_requests() {
    let start = Instant::now();
    let schedule =
        PhaseSchedule::new(start, Duration::from_millis(10), 2_100, 0).expect("valid phase");

    let advance = advance_cursor(&schedule, 0, start + Duration::from_micros(3_500));
    assert_eq!(advance.next_bucket, Some(3));
    assert_eq!(advance.missed_slots, 6);

    let current = schedule.descriptor(3).expect("current bucket");
    assert_eq!(current.first_sequence, 6);
    assert_eq!(current.request_count, 2);
}

#[test]
fn phase_boundaries_and_wall_projection_use_explicit_origins() {
    let mono = Instant::now();
    let wall = UNIX_EPOCH + Duration::from_secs(10);
    let clock = RunClock::new(mono, wall);

    assert_eq!(
        clock
            .unix_nanos(mono + Duration::from_millis(250))
            .expect("projected timestamp"),
        10_250_000_000
    );

    let bootstrap =
        PhaseSchedule::new(mono, Duration::from_millis(2), 1_000, 0).expect("bootstrap");
    let steady = PhaseSchedule::new(
        bootstrap.end(),
        Duration::from_millis(3),
        1_500,
        bootstrap.planned_slots(),
    )
    .expect("steady");
    let active_start = mono + Duration::from_secs(2);
    let active = PhaseSchedule::new(
        active_start,
        Duration::from_millis(1),
        2_100,
        bootstrap.planned_slots() + steady.planned_slots(),
    )
    .expect("active");

    assert_eq!(steady.start(), bootstrap.end());
    assert_eq!(active.start(), active_start);
    assert_eq!(active.first_sequence(), 6);
}

#[test]
fn bounded_channel_never_turns_a_full_bucket_into_later_load() {
    let start = Instant::now() + Duration::from_millis(20);
    let schedule =
        PhaseSchedule::new(start, Duration::from_millis(10), 2_000, 0).expect("valid phase");
    let (sender, mut receiver) = tokio::sync::mpsc::channel(1);

    let handle = spawn_pacer(schedule, sender).expect("pacer thread");
    let metrics = handle.join().expect("pacer did not panic");
    let queued = receiver.blocking_recv().expect("one descriptor");

    assert_eq!(queued.bucket_index, 0);
    assert_eq!(metrics.planned_slots, 20);
    assert_eq!(metrics.dispatched_slots, 2);
    assert_eq!(metrics.missed_slots, 18);
    assert_eq!(metrics.dispatched_slots + metrics.missed_slots, 20);
}

#[test]
fn real_clock_pacing_preserves_the_one_millisecond_envelope() {
    let start = Instant::now() + Duration::from_millis(20);
    let schedule =
        PhaseSchedule::new(start, Duration::from_millis(100), 2_100, 0).expect("valid phase");
    let (sender, mut receiver) = tokio::sync::mpsc::channel(1);
    let handle = spawn_pacer(schedule, sender).expect("pacer thread");

    let mut descriptors = Vec::new();
    while let Some(descriptor) = receiver.blocking_recv() {
        descriptors.push(descriptor);
    }
    let metrics = handle.join().expect("pacer did not panic");

    assert!(!descriptors.is_empty());
    assert!(descriptors.iter().all(|descriptor| {
        descriptor.request_count <= 3
            && descriptor.bucket_deadline == descriptor.bucket_start + Duration::from_millis(1)
    }));
    assert_eq!(metrics.planned_slots, 210);
    assert_eq!(metrics.dispatched_slots + metrics.missed_slots, 210);
    assert!(metrics.pacer_lateness.count > 0);
    assert!(metrics.spin_wall_time_ns > 0);
}

#[test]
fn clock_rejects_instants_before_its_origin() {
    let mono = Instant::now();
    let clock = RunClock::new(mono, SystemTime::now());

    let error = clock
        .unix_nanos(mono - Duration::from_nanos(1))
        .expect_err("time travel must fail");
    assert!(error.to_string().contains("before run monotonic origin"));
}
