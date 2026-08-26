use std::future::Future;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::time::{Duration, Instant};

use anyhow::Result;
use bytes::Bytes;
use rust_loadtool::http2::{Http2Client, Http2Config, Http2Reservation, HttpAttempt};
use rust_loadtool::original::{AdmissionResult, submit_original};
use rust_loadtool::pacer::BucketGate;
use rust_loadtool::payment_state::PaymentStates;

#[derive(Clone)]
struct FakeClient {
    mode: FakeMode,
    sends: Arc<AtomicUsize>,
    states: Arc<PaymentStates>,
    obligation_registered: Arc<AtomicBool>,
    replay_started: Arc<AtomicBool>,
}

#[derive(Clone, Copy)]
enum FakeMode {
    Ready(HttpAttempt),
    Unavailable,
    Late(HttpAttempt),
}

struct FakeReservation {
    attempt: HttpAttempt,
    sends: Arc<AtomicUsize>,
    states: Arc<PaymentStates>,
    obligation_registered: Arc<AtomicBool>,
    replay_started: Arc<AtomicBool>,
}

impl Http2Client for FakeClient {
    type Reservation = FakeReservation;

    fn reserve_until(
        &self,
        deadline: Instant,
    ) -> impl Future<Output = Result<Option<Self::Reservation>>> + Send {
        let client = self.clone();
        async move {
            match client.mode {
                FakeMode::Ready(attempt) => Ok(Some(client.reservation(attempt))),
                FakeMode::Unavailable => {
                    tokio::time::sleep_until(deadline.into()).await;
                    Ok(None)
                }
                FakeMode::Late(attempt) => {
                    tokio::time::sleep(Duration::from_millis(3)).await;
                    Ok(Some(client.reservation(attempt)))
                }
            }
        }
    }
}

impl FakeClient {
    fn reservation(&self, attempt: HttpAttempt) -> FakeReservation {
        FakeReservation {
            attempt,
            sends: Arc::clone(&self.sends),
            states: Arc::clone(&self.states),
            obligation_registered: Arc::clone(&self.obligation_registered),
            replay_started: Arc::clone(&self.replay_started),
        }
    }
}

impl Http2Reservation for FakeReservation {
    async fn send(self, _path: &str, _body: Bytes, _deadline: Instant) -> HttpAttempt {
        assert!(self.states.is_committed(0), "send happened before commit");
        assert!(
            self.obligation_registered.load(Ordering::Acquire),
            "send happened before replay registration"
        );
        assert!(
            self.replay_started.load(Ordering::Acquire),
            "send happened before the committed replay task was created"
        );
        self.sends.fetch_add(1, Ordering::Relaxed);
        self.attempt
    }
}

fn client(mode: FakeMode, states: Arc<PaymentStates>) -> FakeClient {
    FakeClient {
        mode,
        sends: Arc::new(AtomicUsize::new(0)),
        states,
        obligation_registered: Arc::new(AtomicBool::new(false)),
        replay_started: Arc::new(AtomicBool::new(false)),
    }
}

#[tokio::test(flavor = "current_thread")]
async fn expired_initial_deadline_has_no_payload_state_or_request() {
    let states = Arc::new(PaymentStates::new(1));
    let client = client(
        FakeMode::Ready(HttpAttempt::http2(200)),
        Arc::clone(&states),
    );
    let builds = AtomicUsize::new(0);
    let gate = BucketGate::released();

    let result = submit_original(
        &client,
        &states,
        0,
        gate.as_ref(),
        Instant::now() - Duration::from_millis(1),
        Duration::from_secs(5),
        Instant::now() + Duration::from_secs(1),
        || {
            builds.fetch_add(1, Ordering::Relaxed);
            Ok(Bytes::from_static(b"body"))
        },
        |_, _| Ok(()),
        |_, _, _| Ok(()),
    )
    .await
    .unwrap();

    assert_eq!(result, AdmissionResult::Missed);
    assert_eq!(builds.load(Ordering::Relaxed), 0);
    assert!(!states.is_committed(0));
    assert_eq!(client.sends.load(Ordering::Relaxed), 0);
}

#[tokio::test(flavor = "current_thread")]
async fn unavailable_or_late_stream_capacity_remains_unobserved() {
    for mode in [
        FakeMode::Unavailable,
        FakeMode::Late(HttpAttempt::http2(200)),
    ] {
        let states = Arc::new(PaymentStates::new(1));
        let client = client(mode, Arc::clone(&states));
        let gate = BucketGate::released();
        let result = submit_original(
            &client,
            &states,
            0,
            gate.as_ref(),
            Instant::now() + Duration::from_millis(1),
            Duration::from_secs(5),
            Instant::now() + Duration::from_secs(1),
            || Ok(Bytes::from_static(b"body")),
            |_, _| Ok(()),
            |_, _, _| Ok(()),
        )
        .await
        .unwrap();

        assert_eq!(result, AdmissionResult::Missed);
        assert!(!states.is_committed(0));
        assert_eq!(client.sends.load(Ordering::Relaxed), 0);
    }
}

#[tokio::test(flavor = "current_thread")]
async fn committed_http_failure_is_observed_and_never_becomes_missed() {
    let states = Arc::new(PaymentStates::new(1));
    let client = client(HttpAttempt::failed().into(), Arc::clone(&states));
    let obligation = Arc::clone(&client.obligation_registered);
    let replay_started = Arc::clone(&client.replay_started);
    let bucket_start = Instant::now() + Duration::from_millis(20);
    let gate = BucketGate::pending();
    let release_gate = Arc::clone(&gate);
    std::thread::spawn(move || {
        std::thread::sleep(bucket_start.saturating_duration_since(Instant::now()));
        release_gate.release();
    });

    let result = submit_original(
        &client,
        &states,
        0,
        gate.as_ref(),
        bucket_start + Duration::from_millis(10),
        Duration::from_secs(5),
        Instant::now() + Duration::from_secs(2),
        || Ok(Bytes::from_static(b"body")),
        move |_, body| {
            assert_eq!(body, Bytes::from_static(b"body"));
            obligation.store(true, Ordering::Release);
            Ok(())
        },
        move |_, _, _| {
            replay_started.store(true, Ordering::Release);
            Ok(())
        },
    )
    .await
    .unwrap();

    let AdmissionResult::Completed(completion) = result else {
        panic!("committed request became missed");
    };
    assert_eq!(completion.attempt, HttpAttempt::failed());
    assert!(completion.request_started_at >= bucket_start);
    assert!(completion.request_done_at >= completion.request_started_at);
    assert!(states.is_committed(0));
    assert_eq!(client.sends.load(Ordering::Relaxed), 1);
}

#[tokio::test(flavor = "current_thread")]
async fn non_http2_response_is_an_operational_error_after_commit() {
    let states = Arc::new(PaymentStates::new(1));
    let client = client(
        FakeMode::Ready(HttpAttempt::http1(200)),
        Arc::clone(&states),
    );
    let obligation = Arc::clone(&client.obligation_registered);
    let replay_started = Arc::clone(&client.replay_started);
    let gate = BucketGate::released();

    let error = submit_original(
        &client,
        &states,
        0,
        gate.as_ref(),
        Instant::now() + Duration::from_secs(1),
        Duration::from_secs(5),
        Instant::now() + Duration::from_secs(2),
        || Ok(Bytes::from_static(b"body")),
        move |_, _| {
            obligation.store(true, Ordering::Release);
            Ok(())
        },
        move |_, _, _| {
            replay_started.store(true, Ordering::Release);
            Ok(())
        },
    )
    .await
    .expect_err("HTTP/1.1 must not qualify");

    assert!(error.to_string().contains("HTTP/2"));
    assert!(states.is_committed(0));
}

impl From<HttpAttempt> for FakeMode {
    fn from(value: HttpAttempt) -> Self {
        Self::Ready(value)
    }
}

#[tokio::test(flavor = "current_thread")]
async fn transport_rejects_non_tls_endpoints_before_connecting() {
    let config = Http2Config::new(
        "http://localhost:8001",
        "/missing/ca.crt",
        "/missing/clients",
        "localhost",
    );

    let error = config
        .connect("10000001")
        .await
        .expect_err("HTTP must not be accepted");
    assert!(error.to_string().contains("must use https"));
}
