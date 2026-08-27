use std::time::{Duration, Instant};

use anyhow::{Result, bail};
use bytes::Bytes;

use crate::causal::{CausalCapacity, CausalKind, CausalPermit};
use crate::http2::{Http2Client, Http2Reservation, HttpAttempt, PreparedHttp2Request};
use crate::lifecycle::http_deadline;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct CausalCompletion {
    pub attempt: HttpAttempt,
    pub request_started_at: Instant,
    pub request_done_at: Instant,
}

pub async fn send_causal<C: Http2Client>(
    client: &C,
    capacity: &CausalCapacity,
    kind: CausalKind,
    path: &str,
    body: Bytes,
    hard_deadline: Instant,
) -> Result<HttpAttempt> {
    let permit = capacity.try_acquire(kind)?;
    Ok(send_causal_admitted(
        client,
        permit,
        path,
        body,
        Duration::MAX,
        hard_deadline,
        |_| {},
    )
    .await?
    .attempt)
}

pub async fn send_causal_admitted<C, BeforeStart>(
    client: &C,
    _permit: CausalPermit,
    path: &str,
    body: Bytes,
    request_timeout: Duration,
    hard_deadline: Instant,
    before_start: BeforeStart,
) -> Result<CausalCompletion>
where
    C: Http2Client,
    BeforeStart: FnOnce(Instant),
{
    let Some(reservation) = client.reserve_until(hard_deadline).await? else {
        bail!("causal HTTP reached the experiment deadline before sender readiness");
    };
    let request = reservation.prepare(path, body)?;
    let request_started_at = Instant::now();
    before_start(request_started_at);
    let request_deadline = http_deadline(request_started_at, request_timeout, hard_deadline);
    let attempt = request.start(request_deadline).await;
    let request_done_at = Instant::now();
    if attempt.used_http1() {
        bail!("causal central transfer response did not use HTTP/2");
    }
    Ok(CausalCompletion {
        attempt,
        request_started_at,
        request_done_at,
    })
}

pub async fn send_replay<C: Http2Client>(
    client: &C,
    capacity: Option<&CausalCapacity>,
    due_at: Instant,
    path: &str,
    body: Bytes,
    hard_deadline: Instant,
) -> Result<HttpAttempt> {
    tokio::time::sleep_until(due_at.into()).await;
    if Instant::now() >= hard_deadline {
        bail!("replay reached the experiment deadline before starting");
    }
    if let Some(capacity) = capacity {
        send_causal(
            client,
            capacity,
            CausalKind::Replay,
            path,
            body,
            hard_deadline,
        )
        .await
    } else {
        let Some(reservation) = client.reserve_until(hard_deadline).await? else {
            bail!("replay reached the experiment deadline before sender readiness");
        };
        let attempt = reservation.send(path, body, hard_deadline).await;
        if attempt.used_http1() {
            bail!("replay central transfer response did not use HTTP/2");
        }
        Ok(attempt)
    }
}
