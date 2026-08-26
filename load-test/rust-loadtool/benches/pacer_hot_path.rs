use std::convert::Infallible;
use std::hint::black_box;
use std::time::{Duration, Instant};

use anyhow::{Context, Result, ensure};
use bytes::Bytes;
use hdrhistogram::Histogram;
use http_body_util::{BodyExt, Empty, Full};
use hyper::client::conn::http2::SendRequest;
use hyper::server::conn::http2;
use hyper::service::service_fn;
use hyper::{Request, Response};
use hyper_util::rt::{TokioExecutor, TokioIo};
use loadtool_generator::payload::pacs008;
use loadtool_generator::payment_state::PaymentStates;
use tokio::net::{TcpListener, TcpStream};
use tokio::task::JoinHandle;

const REQUESTS_PER_BUCKET: usize = 21;
const WARMUP_BUCKETS: usize = 1_000;
const MEASURED_BUCKETS: usize = 10_000;
const BUCKET_BUDGET: Duration = Duration::from_millis(10);

struct PreparedRequest {
    sender: SendRequest<Full<Bytes>>,
    request: Request<Full<Bytes>>,
}

struct LocalHttp2 {
    sender: SendRequest<Full<Bytes>>,
    connection: JoinHandle<()>,
    server: JoinHandle<()>,
}

#[tokio::main(flavor = "current_thread")]
async fn main() -> Result<()> {
    let http2 = LocalHttp2::start().await?;
    let body = pacs008(
        "E00000000202608260000000000000001",
        "00000001",
        "00000002",
        12_345,
        "2026-08-26T12:00:00Z",
    )?;
    let total_buckets = WARMUP_BUCKETS + MEASURED_BUCKETS;
    let states = PaymentStates::new(total_buckets * REQUESTS_PER_BUCKET);
    let mut histogram = Histogram::<u64>::new(3)?;
    let mut deadline_breaches = 0u64;
    let mut sequence = 0usize;

    for bucket in 0..total_buckets {
        let prepared = prepare_bucket(&http2.sender, &body).await?;
        let response_futures = Vec::with_capacity(REQUESTS_PER_BUCKET);
        let started_at = Instant::now();
        let bucket_deadline = started_at + BUCKET_BUDGET;
        let mut responses = response_futures;

        for PreparedRequest {
            mut sender,
            request,
        } in prepared
        {
            if Instant::now() >= bucket_deadline {
                deadline_breaches += 1;
            }
            ensure!(
                states.commit(sequence as u64),
                "sequence {sequence} committed twice"
            );
            sequence += 1;
            responses.push(sender.send_request(request));
        }

        let completion = tokio::spawn(async move {
            let mut completed = 0usize;
            for response in responses {
                let response = response.await.context("receive benchmark response")?;
                ensure!(response.status().is_success(), "benchmark response failed");
                response
                    .into_body()
                    .collect()
                    .await
                    .context("drain benchmark response")?;
                completed += 1;
            }
            Ok::<_, anyhow::Error>(completed)
        });
        let elapsed = started_at.elapsed();

        let completed = completion.await.context("join bucket completion task")??;
        ensure!(
            completed == REQUESTS_PER_BUCKET,
            "bucket completed {completed} requests"
        );
        if bucket >= WARMUP_BUCKETS {
            histogram.record(elapsed.as_nanos().max(1) as u64)?;
        }
        black_box(completed);
    }

    print_results(&histogram, deadline_breaches);
    http2.connection.abort();
    http2.server.abort();
    Ok(())
}

async fn prepare_bucket(
    sender: &SendRequest<Full<Bytes>>,
    body: &Bytes,
) -> Result<Vec<PreparedRequest>> {
    let mut prepared = Vec::with_capacity(REQUESTS_PER_BUCKET);
    for _ in 0..REQUESTS_PER_BUCKET {
        let mut reservation = sender.clone();
        reservation.ready().await.context("reserve HTTP/2 stream")?;
        let request = Request::builder()
            .method("POST")
            .uri("http://127.0.0.1/transfer")
            .header("content-type", "application/octet-stream")
            .body(Full::new(body.clone()))?;
        prepared.push(PreparedRequest {
            sender: reservation,
            request,
        });
    }
    Ok(prepared)
}

fn print_results(histogram: &Histogram<u64>, deadline_breaches: u64) {
    let micros = |value: u64| value as f64 / 1_000.0;
    let p99_ns = histogram.value_at_quantile(0.99);
    println!("pacer hot-path microbenchmark");
    println!("requests_per_bucket={REQUESTS_PER_BUCKET}");
    println!("measured_buckets={MEASURED_BUCKETS}");
    println!("mean_us={:.3}", histogram.mean() / 1_000.0);
    println!("p50_us={:.3}", micros(histogram.value_at_quantile(0.50)));
    println!("p95_us={:.3}", micros(histogram.value_at_quantile(0.95)));
    println!("p99_us={:.3}", micros(p99_ns));
    println!("max_us={:.3}", micros(histogram.max()));
    println!(
        "p99_bucket_budget_percent={:.4}",
        p99_ns as f64 * 100.0 / BUCKET_BUDGET.as_nanos() as f64
    );
    println!("deadline_breaches={deadline_breaches}");
}

impl LocalHttp2 {
    async fn start() -> Result<Self> {
        let listener = TcpListener::bind(("127.0.0.1", 0))
            .await
            .context("bind benchmark HTTP/2 server")?;
        let address = listener.local_addr()?;
        let server = tokio::spawn(async move {
            let Ok((stream, _)) = listener.accept().await else {
                return;
            };
            let service =
                service_fn(|_| async { Ok::<_, Infallible>(Response::new(Empty::<Bytes>::new())) });
            let _ = http2::Builder::new(TokioExecutor::new())
                .serve_connection(TokioIo::new(stream), service)
                .await;
        });

        let stream = TcpStream::connect(address)
            .await
            .context("connect benchmark HTTP/2 client")?;
        stream.set_nodelay(true)?;
        let (sender, connection) =
            hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(stream))
                .await
                .context("handshake benchmark HTTP/2 connection")?;
        let connection = tokio::spawn(async move {
            let _ = connection.await;
        });
        Ok(Self {
            sender,
            connection,
            server,
        })
    }
}
