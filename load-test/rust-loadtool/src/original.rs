use std::time::Instant;

use anyhow::{Result, anyhow};
use bytes::Bytes;

use crate::http2::{Http2Client, Http2Reservation, HttpAttempt};
use crate::payment_state::PaymentStates;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AdmissionResult {
    Missed,
    Completed(HttpAttempt),
}

pub async fn submit_original<C, Build, Register>(
    client: &C,
    states: &PaymentStates,
    sequence: u64,
    bucket_deadline: Instant,
    hard_deadline: Instant,
    build_payload: Build,
    register_obligations: Register,
) -> Result<AdmissionResult>
where
    C: Http2Client,
    Build: FnOnce() -> Result<Bytes>,
    Register: FnOnce(u64, Bytes) -> Result<()>,
{
    if Instant::now() >= bucket_deadline {
        return Ok(AdmissionResult::Missed);
    }
    let body = build_payload()?;
    let Some(reservation) = client.reserve_until(bucket_deadline).await? else {
        return Ok(AdmissionResult::Missed);
    };
    if Instant::now() >= bucket_deadline {
        return Ok(AdmissionResult::Missed);
    }

    register_obligations(sequence, body.clone())?;
    if !states.commit(sequence) {
        return Err(anyhow!("payment sequence {sequence} was already committed"));
    }
    let attempt = reservation.send("/transfer", body, hard_deadline).await;
    if attempt.used_http1() {
        return Err(anyhow!(
            "central transfer response for sequence {sequence} did not use HTTP/2"
        ));
    }
    Ok(AdmissionResult::Completed(attempt))
}
