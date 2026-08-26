use std::time::Instant;

use anyhow::{Result, bail};
use bytes::Bytes;

use crate::causal::{CausalCapacity, CausalKind, CausalPermit};
use crate::http2::{Http2Client, Http2Reservation, HttpAttempt};

pub async fn send_causal<C: Http2Client>(
    client: &C,
    capacity: &CausalCapacity,
    kind: CausalKind,
    path: &str,
    body: Bytes,
    hard_deadline: Instant,
) -> Result<HttpAttempt> {
    let permit = capacity.try_acquire(kind)?;
    send_causal_admitted(client, permit, path, body, hard_deadline).await
}

pub async fn send_causal_admitted<C: Http2Client>(
    client: &C,
    _permit: CausalPermit,
    path: &str,
    body: Bytes,
    hard_deadline: Instant,
) -> Result<HttpAttempt> {
    let Some(reservation) = client.reserve_until(hard_deadline).await? else {
        bail!("causal HTTP reached the experiment deadline before sender readiness");
    };
    let attempt = reservation.send(path, body, hard_deadline).await;
    if attempt.used_http1() {
        bail!("causal central transfer response did not use HTTP/2");
    }
    Ok(attempt)
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
