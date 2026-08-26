use std::time::{Duration, Instant};

use anyhow::{Result, anyhow};
use bytes::Bytes;

use crate::http2::{Http2Client, Http2Reservation, HttpAttempt};
use crate::pacer::BucketGate;
use crate::payment_state::PaymentStates;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AdmissionResult {
    Missed,
    Completed(OriginalCompletion),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct OriginalCompletion {
    pub attempt: HttpAttempt,
    pub request_started_at: Instant,
    pub request_done_at: Instant,
}

#[allow(clippy::too_many_arguments)]
pub async fn submit_original<C, Build, Register, Start>(
    client: &C,
    states: &PaymentStates,
    sequence: u64,
    bucket_gate: &BucketGate,
    bucket_deadline: Instant,
    request_timeout: Duration,
    hard_deadline: Instant,
    build_payload: Build,
    register_obligations: Register,
    start_committed_work: Start,
) -> Result<AdmissionResult>
where
    C: Http2Client,
    Build: FnOnce() -> Result<Bytes>,
    Register: FnOnce(u64, Bytes) -> Result<()>,
    Start: FnOnce(u64, Bytes, Instant) -> Result<()>,
{
    if Instant::now() >= bucket_deadline {
        return Ok(AdmissionResult::Missed);
    }
    let body = build_payload()?;
    let Some(reservation) = client.reserve_until(bucket_deadline).await? else {
        return Ok(AdmissionResult::Missed);
    };
    bucket_gate.wait().await;
    if Instant::now() >= bucket_deadline {
        return Ok(AdmissionResult::Missed);
    }

    register_obligations(sequence, body.clone())?;
    if !states.commit(sequence) {
        return Err(anyhow!("payment sequence {sequence} was already committed"));
    }
    let request_started_at = Instant::now();
    start_committed_work(sequence, body.clone(), request_started_at)?;
    let request_deadline = request_started_at
        .checked_add(request_timeout)
        .unwrap_or(hard_deadline)
        .min(hard_deadline);
    let attempt = reservation.send("/transfer", body, request_deadline).await;
    let request_done_at = Instant::now();
    if attempt.used_http1() {
        return Err(anyhow!(
            "central transfer response for sequence {sequence} did not use HTTP/2"
        ));
    }
    Ok(AdmissionResult::Completed(OriginalCompletion {
        attempt,
        request_started_at,
        request_done_at,
    }))
}
