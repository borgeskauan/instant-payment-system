use std::future::Future;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::time::{Duration, Instant};

use anyhow::Result;
use bytes::Bytes;
use rust_loadtool::http2::{
    Http2Client, Http2Config, Http2Reservation, HttpAttempt, PreparedHttp2Request,
};
use rust_loadtool::original::{AdmissionMiss, AdmissionOutcome, admit_original, prepare_original};
use rust_loadtool::payment_state::PaymentStates;

#[derive(Clone)]
struct FakeClient {
    mode: FakeMode,
    preparations: Arc<AtomicUsize>,
    sends: Arc<AtomicUsize>,
    states: Arc<PaymentStates>,
    obligation_registered: Arc<AtomicBool>,
}

#[derive(Clone, Copy)]
enum FakeMode {
    Ready(HttpAttempt),
    Unavailable,
    Late(HttpAttempt),
}

struct FakeReservation {
    preparations: Arc<AtomicUsize>,
    attempt: HttpAttempt,
    sends: Arc<AtomicUsize>,
    states: Arc<PaymentStates>,
    obligation_registered: Arc<AtomicBool>,
}

struct FakePreparedRequest {
    attempt: HttpAttempt,
    sends: Arc<AtomicUsize>,
    states: Arc<PaymentStates>,
    obligation_registered: Arc<AtomicBool>,
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
            preparations: Arc::clone(&self.preparations),
            attempt,
            sends: Arc::clone(&self.sends),
            states: Arc::clone(&self.states),
            obligation_registered: Arc::clone(&self.obligation_registered),
        }
    }
}

impl Http2Reservation for FakeReservation {
    type Prepared = FakePreparedRequest;

    fn prepare(self, _path: &str, _body: Bytes) -> Result<Self::Prepared> {
        assert!(
            !self.states.is_committed(0),
            "request construction happened after commit"
        );
        self.preparations.fetch_add(1, Ordering::Relaxed);
        Ok(FakePreparedRequest {
            attempt: self.attempt,
            sends: self.sends,
            states: self.states,
            obligation_registered: self.obligation_registered,
        })
    }
}

impl PreparedHttp2Request for FakePreparedRequest {
    fn start(self, _deadline: Instant) -> impl Future<Output = HttpAttempt> + Send {
        assert!(self.states.is_committed(0), "send happened before commit");
        assert!(
            self.obligation_registered.load(Ordering::Acquire),
            "send happened before replay registration"
        );
        self.sends.fetch_add(1, Ordering::Relaxed);
        std::future::ready(self.attempt)
    }
}

fn client(mode: FakeMode, states: Arc<PaymentStates>) -> FakeClient {
    FakeClient {
        mode,
        preparations: Arc::new(AtomicUsize::new(0)),
        sends: Arc::new(AtomicUsize::new(0)),
        states,
        obligation_registered: Arc::new(AtomicBool::new(false)),
    }
}

#[tokio::test(flavor = "current_thread")]
async fn preparation_is_unobservable_and_admission_starts_http_synchronously() {
    let states = Arc::new(PaymentStates::new(1));
    let client = client(
        FakeMode::Ready(HttpAttempt::http2(200)),
        Arc::clone(&states),
    );
    let obligation = Arc::clone(&client.obligation_registered);
    let builds = AtomicUsize::new(0);
    let bucket_deadline = Instant::now() + Duration::from_secs(1);

    let prepared = prepare_original(&client, 0, bucket_deadline, || {
        builds.fetch_add(1, Ordering::Relaxed);
        Ok(Bytes::from_static(b"body"))
    })
    .await
    .unwrap();
    let AdmissionOutcome::Admitted(prepared) = prepared else {
        panic!("request was not prepared before its bucket");
    };

    assert_eq!(builds.load(Ordering::Relaxed), 1);
    assert_eq!(client.preparations.load(Ordering::Relaxed), 1);
    assert!(!states.is_committed(0));
    assert_eq!(client.sends.load(Ordering::Relaxed), 0);

    obligation.store(true, Ordering::Release);
    let started = admit_original(
        prepared,
        &states,
        Duration::from_secs(5),
        Instant::now() + Duration::from_secs(2),
    )
    .unwrap();
    let AdmissionOutcome::Admitted(started) = started else {
        panic!("prepared request was not admitted inside the bucket");
    };

    assert!(states.is_committed(0));
    assert_eq!(client.sends.load(Ordering::Relaxed), 1);
    assert_eq!(started.finish().await.unwrap().attempt.status, 200);
}

#[tokio::test(flavor = "current_thread")]
async fn expired_initial_deadline_has_no_payload_state_or_request() {
    let states = Arc::new(PaymentStates::new(1));
    let client = client(
        FakeMode::Ready(HttpAttempt::http2(200)),
        Arc::clone(&states),
    );
    let builds = AtomicUsize::new(0);
    let result = prepare_original(
        &client,
        0,
        Instant::now() - Duration::from_millis(1),
        || {
            builds.fetch_add(1, Ordering::Relaxed);
            Ok(Bytes::from_static(b"body"))
        },
    )
    .await
    .unwrap();

    assert!(matches!(
        result,
        AdmissionOutcome::Missed(AdmissionMiss::BeforePreparation)
    ));
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
        let result = prepare_original(
            &client,
            0,
            Instant::now() + Duration::from_millis(1),
            || Ok(Bytes::from_static(b"body")),
        )
        .await
        .unwrap();

        assert!(matches!(
            result,
            AdmissionOutcome::Missed(AdmissionMiss::Http2Readiness)
        ));
        assert!(!states.is_committed(0));
        assert_eq!(client.sends.load(Ordering::Relaxed), 0);
    }
}

#[tokio::test(flavor = "current_thread")]
async fn prepared_request_after_the_bucket_deadline_is_reported_before_commit() {
    let states = Arc::new(PaymentStates::new(1));
    let client = client(
        FakeMode::Ready(HttpAttempt::http2(200)),
        Arc::clone(&states),
    );
    let bucket_deadline = Instant::now() + Duration::from_millis(10);
    let prepared = prepare_original(&client, 0, bucket_deadline, || {
        Ok(Bytes::from_static(b"body"))
    })
    .await
    .unwrap();
    let AdmissionOutcome::Admitted(prepared) = prepared else {
        panic!("request should prepare before its deadline");
    };
    tokio::time::sleep_until((bucket_deadline + Duration::from_millis(1)).into()).await;
    let result = admit_original(
        prepared,
        &states,
        Duration::from_secs(5),
        Instant::now() + Duration::from_secs(1),
    )
    .unwrap();

    assert!(matches!(
        result,
        AdmissionOutcome::Missed(AdmissionMiss::BeforeCommit)
    ));
    assert!(!states.is_committed(0));
    assert_eq!(client.sends.load(Ordering::Relaxed), 0);
}

#[tokio::test(flavor = "current_thread")]
async fn committed_http_failure_is_observed_and_never_becomes_missed() {
    let states = Arc::new(PaymentStates::new(1));
    let client = client(HttpAttempt::failed().into(), Arc::clone(&states));
    let obligation = Arc::clone(&client.obligation_registered);
    let bucket_start = Instant::now() + Duration::from_millis(20);
    let prepared = prepare_original(&client, 0, bucket_start + Duration::from_millis(10), || {
        Ok(Bytes::from_static(b"body"))
    })
    .await
    .unwrap();
    let AdmissionOutcome::Admitted(prepared) = prepared else {
        panic!("request should prepare before its deadline");
    };
    tokio::time::sleep_until(bucket_start.into()).await;
    obligation.store(true, Ordering::Release);
    let started = admit_original(
        prepared,
        &states,
        Duration::from_secs(5),
        Instant::now() + Duration::from_secs(2),
    )
    .unwrap();
    let AdmissionOutcome::Admitted(started) = started else {
        panic!("committed request became missed");
    };
    let completion = started.finish().await.unwrap();
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
    let prepared = prepare_original(&client, 0, Instant::now() + Duration::from_secs(1), || {
        Ok(Bytes::from_static(b"body"))
    })
    .await
    .unwrap();
    let AdmissionOutcome::Admitted(prepared) = prepared else {
        panic!("request should prepare before its deadline");
    };
    obligation.store(true, Ordering::Release);
    let started = admit_original(
        prepared,
        &states,
        Duration::from_secs(5),
        Instant::now() + Duration::from_secs(2),
    )
    .unwrap();
    let AdmissionOutcome::Admitted(started) = started else {
        panic!("committed request became missed");
    };
    let error = started
        .finish()
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
