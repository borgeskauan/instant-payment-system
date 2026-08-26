use std::future::Future;
use std::time::{Duration, Instant};

use anyhow::{Result, anyhow};
use bytes::Bytes;

use crate::http2::{Http2Client, Http2Reservation, HttpAttempt, PreparedHttp2Request};
use crate::payment_state::PaymentStates;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AdmissionMiss {
    BeforePreparation,
    Http2Readiness,
    BeforeCommit,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AdmissionOutcome<T> {
    Missed(AdmissionMiss),
    Admitted(T),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct OriginalCompletion {
    pub attempt: HttpAttempt,
    pub request_started_at: Instant,
    pub request_done_at: Instant,
}

pub struct PreparedOriginal<R> {
    sequence: u64,
    body: Bytes,
    reservation: R,
    bucket_deadline: Instant,
}

pub struct StartedOriginal<F> {
    attempt: F,
    body: Bytes,
    request_started_at: Instant,
}

impl<F> StartedOriginal<F>
where
    F: Future<Output = HttpAttempt>,
{
    pub fn body(&self) -> &Bytes {
        &self.body
    }

    pub fn request_started_at(&self) -> Instant {
        self.request_started_at
    }

    pub async fn finish(self) -> Result<OriginalCompletion> {
        let attempt = self.attempt.await;
        let request_done_at = Instant::now();
        if attempt.used_http1() {
            return Err(anyhow!("central transfer response did not use HTTP/2"));
        }
        Ok(OriginalCompletion {
            attempt,
            request_started_at: self.request_started_at,
            request_done_at,
        })
    }
}

pub async fn prepare_original<C, Build>(
    client: &C,
    sequence: u64,
    bucket_deadline: Instant,
    build_payload: Build,
) -> Result<AdmissionOutcome<PreparedOriginal<<C::Reservation as Http2Reservation>::Prepared>>>
where
    C: Http2Client,
    Build: FnOnce() -> Result<Bytes>,
{
    if Instant::now() >= bucket_deadline {
        return Ok(AdmissionOutcome::Missed(AdmissionMiss::BeforePreparation));
    }
    let body = build_payload()?;
    let Some(reservation) = client.reserve_until(bucket_deadline).await? else {
        return Ok(AdmissionOutcome::Missed(AdmissionMiss::Http2Readiness));
    };
    if Instant::now() >= bucket_deadline {
        return Ok(AdmissionOutcome::Missed(AdmissionMiss::Http2Readiness));
    }
    let request = reservation.prepare("/transfer", body.clone())?;
    Ok(AdmissionOutcome::Admitted(PreparedOriginal {
        sequence,
        body,
        reservation: request,
        bucket_deadline,
    }))
}

pub fn admit_original<R>(
    prepared: PreparedOriginal<R>,
    states: &PaymentStates,
    request_timeout: Duration,
    hard_deadline: Instant,
) -> Result<AdmissionOutcome<StartedOriginal<impl Future<Output = HttpAttempt> + Send + use<R>>>>
where
    R: PreparedHttp2Request,
{
    if Instant::now() >= prepared.bucket_deadline {
        return Ok(AdmissionOutcome::Missed(AdmissionMiss::BeforeCommit));
    }

    if !states.commit(prepared.sequence) {
        return Err(anyhow!(
            "payment sequence {} was already committed",
            prepared.sequence
        ));
    }
    let request_started_at = Instant::now();
    let request_deadline = request_started_at
        .checked_add(request_timeout)
        .unwrap_or(hard_deadline)
        .min(hard_deadline);
    let attempt = prepared.reservation.start(request_deadline);
    Ok(AdmissionOutcome::Admitted(StartedOriginal {
        attempt,
        body: prepared.body,
        request_started_at,
    }))
}
