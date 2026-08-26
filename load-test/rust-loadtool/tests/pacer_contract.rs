use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use loadtool_generator::clock::RunClock;
use loadtool_generator::pacer::{
    PhaseSchedule, PreparedBucket, advance_cursor, spawn_prepared_pacer,
};

#[test]
fn prepared_buckets_are_admitted_by_the_native_pacer_at_their_own_boundary() {
    let start = Instant::now() + Duration::from_millis(30);
    let schedule =
        PhaseSchedule::new(start, Duration::from_millis(10), 100, 0).expect("valid phase");
    let (descriptor_sender, mut descriptor_receiver) = tokio::sync::mpsc::channel(1);
    let (prepared_sender, prepared_receiver) = std::sync::mpsc::channel();
    let admissions = Arc::new(Mutex::new(Vec::new()));
    let observed = Arc::clone(&admissions);

    let handle = spawn_prepared_pacer(
        schedule,
        descriptor_sender,
        prepared_receiver,
        move |bucket| {
            observed
                .lock()
                .expect("admission lock")
                .push((bucket.payload, Instant::now()));
        },
    )
    .expect("pacer thread");
    let descriptor = descriptor_receiver
        .blocking_recv()
        .expect("preparation descriptor");
    prepared_sender
        .send(PreparedBucket::new(descriptor.bucket_index, "ready"))
        .expect("prepared bucket");
    let metrics = handle.join().expect("pacer did not panic");

    let admissions = admissions.lock().expect("admission lock");
    assert_eq!(admissions.len(), 1);
    assert_eq!(admissions[0].0, "ready");
    assert!(admissions[0].1 >= start);
    assert!(admissions[0].1 < start + Duration::from_millis(10));
    assert_eq!(metrics.missed_slots, 0);
}

#[test]
fn absent_preparation_expires_without_delaying_the_next_bucket() {
    let start = Instant::now() + Duration::from_millis(30);
    let schedule =
        PhaseSchedule::new(start, Duration::from_millis(20), 100, 0).expect("valid phase");
    let (descriptor_sender, mut descriptor_receiver) = tokio::sync::mpsc::channel(2);
    let (prepared_sender, prepared_receiver) = std::sync::mpsc::channel();
    let admissions = Arc::new(Mutex::new(Vec::new()));
    let observed_admissions = Arc::clone(&admissions);

    let handle = spawn_prepared_pacer(
        schedule,
        descriptor_sender,
        prepared_receiver,
        move |bucket| {
            observed_admissions.lock().expect("admission lock").push((
                bucket.bucket_index,
                bucket.payload,
                Instant::now(),
            ));
        },
    )
    .expect("pacer thread");
    let first = descriptor_receiver
        .blocking_recv()
        .expect("first descriptor");
    let second = descriptor_receiver
        .blocking_recv()
        .expect("second descriptor");
    assert_eq!(first.bucket_index, 0);
    assert_eq!(second.bucket_index, 1);
    prepared_sender
        .send(PreparedBucket::new(second.bucket_index, "second"))
        .expect("second prepared bucket");
    let metrics = handle.join().expect("pacer did not panic");

    assert_eq!(metrics.missed_preparation_not_ready, first.request_count);
    let admissions = admissions.lock().expect("admission lock");
    assert_eq!(admissions.len(), 1);
    assert_eq!(admissions[0].0, 1);
    assert_eq!(admissions[0].1, "second");
    assert!(admissions[0].2 >= second.bucket_start);
    assert!(admissions[0].2 < second.bucket_deadline);
    assert_eq!(metrics.missed_slots, first.request_count);
}

#[test]
fn descriptors_keep_absolute_bucket_boundaries_and_sequence_positions() {
    let start = Instant::now() + Duration::from_secs(1);
    let schedule =
        PhaseSchedule::new(start, Duration::from_millis(1_000), 2_100, 17).expect("valid phase");

    let first = schedule.descriptor(0).expect("first bucket");
    assert_eq!(first.bucket_index, 0);
    assert_eq!(first.first_sequence, 17);
    assert_eq!(first.request_count, 21);
    assert_eq!(first.preparation_start, start - Duration::from_millis(20));
    assert_eq!(first.bucket_start, start);
    assert_eq!(first.bucket_deadline, start + Duration::from_millis(10));

    let tenth = schedule.descriptor(9).expect("tenth bucket");
    assert_eq!(tenth.first_sequence, 206);
    assert_eq!(tenth.request_count, 21);
    assert_eq!(tenth.bucket_start, start + Duration::from_millis(90));
    assert_eq!(tenth.bucket_deadline, start + Duration::from_millis(100));
}

#[test]
fn cumulative_arithmetic_distributes_fractional_requests_across_ten_millisecond_buckets() {
    let start = Instant::now() + Duration::from_secs(1);
    let schedule =
        PhaseSchedule::new(start, Duration::from_secs(1), 2_050, 0).expect("valid phase");

    let first = schedule.descriptor(0).expect("first bucket");
    let second = schedule.descriptor(1).expect("second bucket");

    assert_eq!(first.request_count, 20);
    assert_eq!(second.first_sequence, 20);
    assert_eq!(second.request_count, 21);
    assert_eq!(schedule.planned_slots(), 2_050);
}

#[test]
fn late_cursor_skips_expired_buckets_without_moving_their_requests() {
    let start = Instant::now();
    let schedule =
        PhaseSchedule::new(start, Duration::from_millis(100), 2_100, 0).expect("valid phase");

    let advance = advance_cursor(&schedule, 0, start + Duration::from_millis(35));
    assert_eq!(advance.next_bucket, Some(3));
    assert_eq!(advance.missed_slots, 63);

    let current = schedule.descriptor(3).expect("current bucket");
    assert_eq!(current.first_sequence, 63);
    assert_eq!(current.request_count, 21);
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
        PhaseSchedule::new(mono, Duration::from_millis(20), 1_000, 0).expect("bootstrap");
    let steady = PhaseSchedule::new(
        bootstrap.end(),
        Duration::from_millis(30),
        1_500,
        bootstrap.planned_slots(),
    )
    .expect("steady");
    let active_start = mono + Duration::from_secs(2);
    let active = PhaseSchedule::new(
        active_start,
        Duration::from_millis(10),
        2_100,
        bootstrap.planned_slots() + steady.planned_slots(),
    )
    .expect("active");

    assert_eq!(steady.start(), bootstrap.end());
    assert_eq!(active.start(), active_start);
    assert_eq!(active.first_sequence(), 65);
}

#[test]
fn bounded_channel_never_turns_a_full_bucket_into_later_load() {
    let start = Instant::now() + Duration::from_millis(20);
    let schedule =
        PhaseSchedule::new(start, Duration::from_millis(100), 2_000, 0).expect("valid phase");
    let (sender, mut receiver) = tokio::sync::mpsc::channel(1);
    let (_prepared_sender, prepared_receiver) = std::sync::mpsc::channel::<PreparedBucket<()>>();

    let handle =
        spawn_prepared_pacer(schedule, sender, prepared_receiver, |_| {}).expect("pacer thread");
    let metrics = handle.join().expect("pacer did not panic");
    let queued = receiver.blocking_recv().expect("one descriptor");

    assert_eq!(queued.bucket_index, 0);
    assert_eq!(metrics.planned_slots, 200);
    assert_eq!(metrics.dispatched_slots, 20);
    assert_eq!(metrics.missed_slots, 200);
    assert_eq!(metrics.missed_cursor_skip, 0);
    assert_eq!(metrics.missed_expired_before_dispatch, 0);
    assert_eq!(metrics.missed_channel_full, 180);
    assert_eq!(metrics.missed_preparation_not_ready, 20);
}

#[test]
fn expired_phase_separates_preparation_expiry_from_cursor_skip() {
    let start = Instant::now() - Duration::from_secs(1);
    let schedule =
        PhaseSchedule::new(start, Duration::from_millis(100), 2_000, 0).expect("valid phase");
    let (sender, _receiver) = tokio::sync::mpsc::channel(1);
    let (_prepared_sender, prepared_receiver) = std::sync::mpsc::channel::<PreparedBucket<()>>();

    let metrics = spawn_prepared_pacer(schedule, sender, prepared_receiver, |_| {})
        .expect("pacer thread")
        .join()
        .expect("pacer did not panic");

    assert_eq!(metrics.planned_slots, 200);
    assert_eq!(metrics.dispatched_slots, 0);
    assert_eq!(metrics.missed_slots, 200);
    assert!(metrics.missed_expired_before_dispatch > 0);
    assert!(metrics.missed_cursor_skip > 0);
    assert_eq!(
        metrics.missed_expired_before_dispatch + metrics.missed_cursor_skip,
        metrics.missed_slots
    );
    assert_eq!(metrics.missed_channel_full, 0);
}

#[test]
fn real_clock_pacing_preserves_the_ten_millisecond_envelope() {
    let start = Instant::now() + Duration::from_millis(20);
    let schedule =
        PhaseSchedule::new(start, Duration::from_millis(100), 2_100, 0).expect("valid phase");
    let (sender, mut receiver) = tokio::sync::mpsc::channel(1);
    let (prepared_sender, prepared_receiver) = std::sync::mpsc::channel();
    let handle =
        spawn_prepared_pacer(schedule, sender, prepared_receiver, |_| {}).expect("pacer thread");

    let mut descriptors = Vec::new();
    while let Some(descriptor) = receiver.blocking_recv() {
        prepared_sender
            .send(PreparedBucket::new(descriptor.bucket_index, ()))
            .expect("prepared bucket");
        descriptors.push(descriptor);
    }
    let metrics = handle.join().expect("pacer did not panic");

    assert!(!descriptors.is_empty());
    assert!(descriptors.iter().all(|descriptor| {
        descriptor.request_count == 21
            && descriptor.bucket_deadline == descriptor.bucket_start + Duration::from_millis(10)
    }));
    assert_eq!(metrics.planned_slots, 210);
    assert_eq!(metrics.dispatched_slots + metrics.missed_slots, 210);
    assert!(metrics.pacer_lateness.count > 0);
    assert!(metrics.spin_wall_time_ns > 0);
}

#[test]
fn descriptors_arrive_before_the_bucket_for_non_observable_preparation() {
    let start = Instant::now() + Duration::from_millis(50);
    let schedule =
        PhaseSchedule::new(start, Duration::from_millis(50), 2_000, 0).expect("valid phase");
    let (sender, mut receiver) = tokio::sync::mpsc::channel(1);
    let (prepared_sender, prepared_receiver) = std::sync::mpsc::channel();
    let handle =
        spawn_prepared_pacer(schedule, sender, prepared_receiver, |_| {}).expect("pacer thread");

    let first = receiver.blocking_recv().expect("first descriptor");
    let received_at = Instant::now();
    prepared_sender
        .send(PreparedBucket::new(first.bucket_index, ()))
        .expect("first prepared bucket");
    while let Some(descriptor) = receiver.blocking_recv() {
        prepared_sender
            .send(PreparedBucket::new(descriptor.bucket_index, ()))
            .expect("prepared bucket");
    }
    let metrics = handle.join().expect("pacer did not panic");

    assert!(received_at < first.bucket_start);
    assert_eq!(metrics.dispatched_slots, metrics.planned_slots);
    assert_eq!(metrics.missed_slots, 0);
}

#[test]
fn rates_below_one_thousand_do_not_dispatch_empty_buckets() {
    let start = Instant::now() + Duration::from_millis(20);
    let schedule =
        PhaseSchedule::new(start, Duration::from_millis(100), 50, 0).expect("valid low-rate phase");
    let (sender, mut receiver) = tokio::sync::mpsc::channel(1);
    let (prepared_sender, prepared_receiver) = std::sync::mpsc::channel();
    let handle =
        spawn_prepared_pacer(schedule, sender, prepared_receiver, |_| {}).expect("pacer thread");

    let mut descriptors = Vec::new();
    while let Some(descriptor) = receiver.blocking_recv() {
        prepared_sender
            .send(PreparedBucket::new(descriptor.bucket_index, ()))
            .expect("prepared bucket");
        descriptors.push(descriptor);
    }
    let metrics = handle.join().expect("pacer did not panic");

    assert_eq!(descriptors.len(), 5);
    assert!(
        descriptors
            .iter()
            .all(|descriptor| descriptor.request_count == 1)
    );
    assert_eq!(metrics.dispatched_slots, 5);
    assert_eq!(metrics.missed_slots, 0);
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
