use std::sync::atomic::{AtomicBool, Ordering};

use super::*;
use http::Response;
use tokio::io::duplex;
use tokio::sync::oneshot;

async fn limited_client() -> (
    PersistentHttp2Client,
    oneshot::Receiver<()>,
    oneshot::Sender<()>,
) {
    let (client_io, server_io) = duplex(64 * 1024);
    let (first_received_tx, first_received_rx) = oneshot::channel();
    let (release_first_tx, release_first_rx) = oneshot::channel();
    tokio::spawn(async move {
        let mut builder = h2::server::Builder::new();
        builder.max_concurrent_streams(1);
        let mut connection = builder.handshake::<_, Bytes>(server_io).await.unwrap();
        let mut first_received_tx = Some(first_received_tx);
        let mut release_first_rx = Some(release_first_rx);
        while let Some(request) = connection.accept().await {
            let (_request, mut respond) = request.unwrap();
            if let Some(first_received_tx) = first_received_tx.take() {
                first_received_tx.send(()).unwrap();
                let release_first_rx = release_first_rx.take().unwrap();
                tokio::spawn(async move {
                    let _ = release_first_rx.await;
                    let _ = respond.send_response(Response::new(()), true);
                });
            } else {
                respond.send_response(Response::new(()), true).unwrap();
            }
        }
    });

    let mut builder = h2::client::Builder::new();
    builder.initial_max_send_streams(0);
    let (sender, connection) = builder.handshake(client_io).await.unwrap();
    tokio::spawn(async move {
        let _ = connection.await;
    });
    let max_concurrent_streams = wait_for_stream_limit(&sender).await.unwrap();
    (
        PersistentHttp2Client {
            authority: "example.test".to_owned(),
            sender: Arc::new(Mutex::new(sender)),
            capacity: Arc::new(Semaphore::new(max_concurrent_streams)),
            max_concurrent_streams,
        },
        first_received_rx,
        release_first_tx,
    )
}

fn start(
    request: PersistentPreparedRequest,
    timeout: Duration,
    committed: &AtomicBool,
) -> impl Future<Output = HttpAttempt> + Send + use<> {
    let mut admit = || {
        committed.store(true, Ordering::Release);
        Ok(())
    };
    let mut before_start = |_| {};
    let HttpStart::Started { attempt, .. } = request
        .start(
            Instant::now() + Duration::from_secs(1),
            timeout,
            Instant::now() + Duration::from_secs(2),
            &mut admit,
            &mut before_start,
        )
        .unwrap()
    else {
        panic!("request unexpectedly missed admission");
    };
    attempt
}

#[tokio::test(flavor = "current_thread")]
async fn advertised_capacity_is_reserved_until_the_response_finishes() {
    let (client, first_received, release_first) = limited_client().await;
    assert_eq!(client.max_concurrent_streams, 1);
    let first = client
        .reserve_until(Instant::now() + Duration::from_secs(1))
        .await
        .unwrap()
        .unwrap()
        .prepare("/first", Bytes::from_static(b"first"))
        .unwrap();
    let committed = AtomicBool::new(false);
    let attempt = start(first, Duration::from_secs(1), &committed);
    first_received.await.unwrap();
    assert!(committed.load(Ordering::Acquire));

    assert!(
        client
            .reserve_until(Instant::now() + Duration::from_millis(20))
            .await
            .unwrap()
            .is_none()
    );

    release_first.send(()).unwrap();
    assert_eq!(attempt.await, HttpAttempt::http2(200));
    assert!(
        client
            .reserve_until(Instant::now() + Duration::from_secs(1))
            .await
            .unwrap()
            .is_some()
    );
}

#[tokio::test(flavor = "current_thread")]
async fn timed_out_stream_releases_protocol_and_local_capacity() {
    let (client, first_received, release_first) = limited_client().await;
    let first = client
        .reserve_until(Instant::now() + Duration::from_secs(1))
        .await
        .unwrap()
        .unwrap()
        .prepare("/first", Bytes::from_static(b"first"))
        .unwrap();
    let committed = AtomicBool::new(false);
    let attempt = start(first, Duration::from_millis(10), &committed);
    first_received.await.unwrap();
    assert_eq!(attempt.await, HttpAttempt::failed());
    let _ = release_first.send(());

    let second = client
        .reserve_until(Instant::now() + Duration::from_secs(1))
        .await
        .unwrap()
        .unwrap()
        .prepare("/second", Bytes::from_static(b"second"))
        .unwrap();
    let second_committed = AtomicBool::new(false);
    assert_eq!(
        start(second, Duration::from_secs(1), &second_committed).await,
        HttpAttempt::http2(200)
    );
}

#[tokio::test(flavor = "current_thread")]
async fn cancelled_attempt_releases_protocol_and_local_capacity() {
    let (client, first_received, release_first) = limited_client().await;
    let first = client
        .reserve_until(Instant::now() + Duration::from_secs(1))
        .await
        .unwrap()
        .unwrap()
        .prepare("/first", Bytes::from_static(b"first"))
        .unwrap();
    let committed = AtomicBool::new(false);
    let attempt = start(first, Duration::from_secs(1), &committed);
    first_received.await.unwrap();
    drop(attempt);
    let _ = release_first.send(());

    let second = client
        .reserve_until(Instant::now() + Duration::from_secs(1))
        .await
        .unwrap()
        .unwrap()
        .prepare("/second", Bytes::from_static(b"second"))
        .unwrap();
    let second_committed = AtomicBool::new(false);
    assert_eq!(
        start(second, Duration::from_secs(1), &second_committed).await,
        HttpAttempt::http2(200)
    );
}

#[tokio::test(flavor = "current_thread")]
async fn omitted_stream_limit_is_rejected() {
    let (client_io, server_io) = duplex(64 * 1024);
    tokio::spawn(async move {
        let _ = h2::server::handshake(server_io).await;
    });
    let mut builder = h2::client::Builder::new();
    builder.initial_max_send_streams(0);
    let (sender, connection) = builder.handshake(client_io).await.unwrap();
    tokio::spawn(async move {
        let _ = connection.await;
    });

    let error = wait_for_stream_limit(&sender)
        .await
        .expect_err("unbounded peer capacity must be rejected");
    assert!(error.to_string().contains("finite"));
}
