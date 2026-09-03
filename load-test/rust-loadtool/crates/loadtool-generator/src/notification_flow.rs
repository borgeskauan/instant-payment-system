use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::time::{Duration, Instant};

use anyhow::Result;
use bytes::Bytes;

use crate::causal::{CausalKind, CausalPermit};
use crate::lifecycle::{offset_ns, rfc3339_now};
use crate::notification::NotificationPayload;
use crate::payload::pacs002;
use crate::phase_tracker::{PhaseTracker, WarmupObservation};
use crate::pull::{ProcessedNotification, PullClient, PullState};
use crate::recorder::EventSender;
use crate::replay_task::{send_causal_admitted, send_replay};
use crate::runtime::{PhaseWork, Runtime};
use loadtool_contract::event::{
    Event, MessageKind, NotificationKind, NotificationStatus, Participant,
};

pub(crate) struct PullSession {
    ispb: String,
    receiver_role: bool,
    client: PullClient,
}

impl PullSession {
    pub(crate) fn new(ispb: String, receiver_role: bool, client: PullClient) -> Self {
        Self {
            ispb,
            receiver_role,
            client,
        }
    }
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn spawn_replay(
    runtime: Arc<Runtime>,
    sequence: u64,
    sender: Participant,
    message: MessageKind,
    path: &'static str,
    body: Bytes,
    request_started_at: Instant,
    hard_deadline: Instant,
    delay: Duration,
    causal: bool,
    tracker: Option<Arc<PhaseTracker>>,
) {
    if !runtime.accepting_work.load(Ordering::Acquire) {
        if let Some(tracker) = tracker {
            tracker.fail(format!(
                "{} replay for sequence {sequence} was created after semantic shutdown",
                message.as_str()
            ));
        }
        return;
    }
    let client_ispb = match runtime.pair_for_sequence(sequence) {
        Ok(pair) => match sender {
            Participant::Payer => pair.payer().to_owned(),
            Participant::Receiver => pair.receiver().to_owned(),
        },
        Err(error) => {
            runtime.failure.operational(&runtime.cancellation, error);
            return;
        }
    };
    let client = match runtime.http_clients.get(&client_ispb) {
        Some(client) => Arc::clone(client),
        None => {
            runtime.failure.operational(
                &runtime.cancellation,
                format!("missing replay HTTP/2 client for {client_ispb}"),
            );
            return;
        }
    };
    let task_runtime = Arc::clone(&runtime);
    runtime.tasks.spawn(async move {
        let _work = PhaseWork::new(tracker.clone());
        let due_at = request_started_at
            .checked_add(delay)
            .unwrap_or(hard_deadline);
        tokio::time::sleep_until(due_at.into()).await;
        let started_at = Instant::now();
        let result = if causal {
            send_replay(
                client.as_ref(),
                Some(task_runtime.causal_capacity.as_ref()),
                started_at,
                path,
                body,
                hard_deadline,
            )
            .await
        } else {
            send_replay(client.as_ref(), None, started_at, path, body, hard_deadline).await
        };
        let done_at = Instant::now();
        match result {
            Ok(attempt) => {
                record_replay(
                    &task_runtime.recorder,
                    task_runtime.clock,
                    sequence,
                    sender,
                    message,
                    started_at,
                    done_at,
                    attempt.status,
                )
                .unwrap_or_else(|error| {
                    task_runtime
                        .failure
                        .operational(&task_runtime.cancellation, error);
                });
                if !(200..300).contains(&attempt.status)
                    && let Some(tracker) = tracker
                {
                    tracker.fail(format!(
                        "warmup replay for sequence {sequence} returned HTTP {}",
                        attempt.status
                    ));
                }
            }
            Err(error) => {
                if let Some(tracker) = tracker {
                    tracker.fail(error.to_string());
                }
            }
        }
    });
}

#[allow(clippy::too_many_arguments)]
fn record_replay(
    recorder: &EventSender,
    clock: crate::clock::RunClock,
    sequence: u64,
    sender: Participant,
    message: MessageKind,
    started_at: Instant,
    done_at: Instant,
    http_status: u16,
) -> Result<()> {
    recorder.record(Event::ReplayCompleted {
        sequence,
        sender,
        message,
        request_started_offset_ns: offset_ns(clock, started_at),
        request_done_offset_ns: offset_ns(clock, done_at),
        http_status,
    })
}

pub(crate) async fn pull_loop(runtime: Arc<Runtime>, mut session: PullSession) {
    let mut state = PullState::new();
    loop {
        let result = tokio::select! {
            _ = runtime.cancellation.cancelled() => return,
            response = session.client.pull(state.cursor()) => response,
        };
        let batch = match result {
            Ok(batch) => batch,
            Err(error) if transient_pull_error(&error) => {
                tokio::select! {
                    _ = runtime.cancellation.cancelled() => return,
                    _ = tokio::time::sleep(Duration::from_millis(100)) => {}
                }
                continue;
            }
            Err(error) => {
                runtime.failure.operational(
                    &runtime.cancellation,
                    format!("notification Pull for {}: {error}", session.ispb),
                );
                return;
            }
        };
        let received_at = Instant::now();
        let mut capacity_violation = None;
        let result = state.process(batch, |notification| {
            process_pulled_notification(
                &runtime,
                &session.ispb,
                session.receiver_role,
                received_at,
                notification,
                &mut capacity_violation,
            )
        });
        if let Err(error) = result {
            if let Some(error) = capacity_violation {
                eprintln!("generator capacity violation: {error}");
            } else {
                runtime.failure.operational(
                    &runtime.cancellation,
                    format!("process notification Pull for {}: {error}", session.ispb),
                );
            }
            return;
        }
    }
}

fn process_pulled_notification(
    runtime: &Arc<Runtime>,
    session_ispb: &str,
    receiver_role: bool,
    received_at: Instant,
    notification: &ProcessedNotification,
    capacity_violation: &mut Option<String>,
) -> Result<()> {
    for payload in &notification.payloads {
        match payload {
            NotificationPayload::Pacs008 { end_to_end_id } => {
                let Some(sequence) = runtime.identity.sequence(end_to_end_id) else {
                    continue;
                };
                let pair = runtime.pair_for_sequence(sequence)?;
                let participant = if session_ispb == pair.receiver() {
                    Participant::Receiver
                } else if session_ispb == pair.payer() {
                    Participant::Payer
                } else {
                    continue;
                };
                runtime.recorder.record(Event::Notification {
                    sequence,
                    participant,
                    kind: NotificationKind::Pacs008Received,
                    received_offset_ns: offset_ns(runtime.clock, received_at),
                    status: NotificationStatus::None,
                    reason_codes: Vec::new(),
                })?;
                if receiver_role
                    && participant == Participant::Receiver
                    && runtime.states.claim_pacs002(sequence)
                    && let Err(error) = spawn_pacs002(Arc::clone(runtime), sequence)
                {
                    if let Some(tracker) = runtime.tracker_for(sequence) {
                        tracker.fail(format!(
                            "warmup PACS.002 for sequence {sequence} exceeded generator capacity: {error}"
                        ));
                    }
                    *capacity_violation = Some(error.to_string());
                    return Err(error);
                }
            }
            NotificationPayload::Pacs002 {
                end_to_end_id,
                status,
                reason_codes,
            } => {
                let Some(sequence) = runtime.identity.sequence(end_to_end_id) else {
                    continue;
                };
                let pair = runtime.pair_for_sequence(sequence)?;
                let participant = if session_ispb == pair.payer() {
                    Participant::Payer
                } else if session_ispb == pair.receiver() {
                    Participant::Receiver
                } else {
                    continue;
                };
                runtime.recorder.record(Event::Notification {
                    sequence,
                    participant,
                    kind: NotificationKind::Pacs002Received,
                    received_offset_ns: offset_ns(runtime.clock, received_at),
                    status: notification_status(status),
                    reason_codes: reason_codes.clone(),
                })?;
                observe_warmup_outcome(runtime, sequence, participant, status, reason_codes)?;
            }
        }
    }
    Ok(())
}

fn observe_warmup_outcome(
    runtime: &Runtime,
    sequence: u64,
    participant: Participant,
    status: &str,
    reason_codes: &[String],
) -> Result<()> {
    if sequence >= runtime.warmup_slots || !runtime.states.is_committed(sequence) {
        return Ok(());
    }
    let payment = runtime.planner.payment(sequence)?;
    let expectation = &runtime.plan.scenarios[payment.scenario_index]
        .expectations
        .payer_notification;
    let Some(matches) = payer_outcome_match(
        participant,
        status,
        reason_codes,
        &expectation.status,
        &expectation.reason_codes,
    ) else {
        return Ok(());
    };
    match runtime.warmup_outcomes.observe(sequence, matches) {
        Some(WarmupObservation::MatchedFirst) => {
            if let Some(tracker) = runtime.tracker_for(sequence) {
                tracker.done()?;
            }
        }
        Some(WarmupObservation::ContradictionFirst) => {
            if let Some(tracker) = runtime.tracker_for(sequence) {
                tracker.fail(format!(
                    "warmup payment {sequence} received contradictory payer outcome"
                ));
            }
        }
        Some(WarmupObservation::MatchedAgain)
        | Some(WarmupObservation::ContradictionAgain)
        | None => {}
    }
    Ok(())
}

fn spawn_pacs002(runtime: Arc<Runtime>, sequence: u64) -> Result<()> {
    let permit = runtime.causal_capacity.try_acquire(CausalKind::Original)?;
    let tracker = runtime.tracker_for(sequence);
    let task_runtime = Arc::clone(&runtime);
    runtime.tasks.spawn(async move {
        run_pacs002(task_runtime, sequence, permit, tracker).await;
    });
    Ok(())
}

async fn run_pacs002(
    runtime: Arc<Runtime>,
    sequence: u64,
    permit: CausalPermit,
    tracker: Option<Arc<PhaseTracker>>,
) {
    let _work = PhaseWork::new(tracker.clone());
    let pair = match runtime.pair_for_sequence(sequence) {
        Ok(pair) => pair,
        Err(error) => {
            runtime.failure.operational(&runtime.cancellation, error);
            return;
        }
    };
    let client = match runtime.http_clients.get(pair.receiver()) {
        Some(client) => Arc::clone(client),
        None => {
            runtime.failure.operational(
                &runtime.cancellation,
                format!("missing causal HTTP/2 client for {}", pair.receiver()),
            );
            return;
        }
    };
    let hard_deadline = runtime.hard_deadline_for(sequence);
    let body = match pacs002(&runtime.identity.end_to_end_id(sequence), &rfc3339_now()) {
        Ok(body) => body,
        Err(error) => {
            runtime.failure.operational(&runtime.cancellation, error);
            return;
        }
    };
    let payment = match runtime.planner.payment(sequence) {
        Ok(payment) => payment,
        Err(error) => {
            runtime.failure.operational(&runtime.cancellation, error);
            return;
        }
    };
    let replay_delay = payment.pacs002_ordinal.and_then(|ordinal| {
        runtime
            .pacs002_replay
            .as_ref()
            .and_then(|(selector, delay)| selector.selected(ordinal).then_some(*delay))
    });
    let mut replay_body = replay_delay.map(|_| body.clone());
    let mut replay_selected = false;
    let completion = send_causal_admitted(
        client.as_ref(),
        permit,
        "/transfer/status",
        body,
        runtime.request_timeout_for(sequence),
        hard_deadline,
        |started_at| {
            replay_selected = replay_delay.is_some() && runtime.before_generation_end(started_at);
            if replay_selected {
                spawn_replay(
                    Arc::clone(&runtime),
                    sequence,
                    Participant::Receiver,
                    MessageKind::Pacs002,
                    "/transfer/status",
                    replay_body
                        .take()
                        .expect("selected PACS.002 replay retained its body"),
                    started_at,
                    hard_deadline,
                    replay_delay.expect("selected PACS.002 replay retained its delay"),
                    true,
                    tracker.clone(),
                );
            }
        },
    )
    .await;
    match completion {
        Ok(completion) => {
            if let Err(error) = runtime.recorder.record(Event::Pacs002Completed {
                sequence,
                request_started_offset_ns: offset_ns(runtime.clock, completion.request_started_at),
                request_done_offset_ns: offset_ns(runtime.clock, completion.request_done_at),
                http_status: completion.attempt.status,
                replay_selected,
            }) {
                runtime.failure.operational(&runtime.cancellation, error);
                return;
            }
            if (200..300).contains(&completion.attempt.status) {
                if let Err(error) = runtime.recorder.record(Event::Notification {
                    sequence,
                    participant: Participant::Receiver,
                    kind: NotificationKind::Pacs002Sent,
                    received_offset_ns: offset_ns(runtime.clock, completion.request_done_at),
                    status: NotificationStatus::None,
                    reason_codes: Vec::new(),
                }) {
                    runtime.failure.operational(&runtime.cancellation, error);
                }
            } else if let Some(tracker) = tracker {
                tracker.fail(format!(
                    "warmup PACS.002 for sequence {sequence} returned HTTP {}",
                    completion.attempt.status
                ));
            }
        }
        Err(error) => runtime.failure.operational(&runtime.cancellation, error),
    }
}

fn transient_pull_error(error: &anyhow::Error) -> bool {
    error.downcast_ref::<tonic::Status>().is_some_and(|status| {
        matches!(
            status.code(),
            tonic::Code::Unavailable | tonic::Code::DeadlineExceeded
        )
    })
}

fn notification_status(status: &str) -> NotificationStatus {
    match status {
        "" => NotificationStatus::None,
        "ACSC" => NotificationStatus::Acsc,
        "RJCT" => NotificationStatus::Rjct,
        other => NotificationStatus::Other(other.to_owned()),
    }
}

fn same_reasons(left: &[String], right: &[String]) -> bool {
    let mut left = left.to_vec();
    let mut right = right.to_vec();
    left.sort();
    right.sort();
    left == right
}

fn payer_outcome_match(
    participant: Participant,
    status: &str,
    reason_codes: &[String],
    expected_status: &str,
    expected_reason_codes: &[String],
) -> Option<bool> {
    (participant == Participant::Payer)
        .then(|| status == expected_status && same_reasons(reason_codes, expected_reason_codes))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_payer_notifications_participate_in_business_outcome_matching() {
        assert_eq!(
            payer_outcome_match(Participant::Receiver, "ACSC", &[], "ACSC", &[]),
            None
        );
        assert_eq!(
            payer_outcome_match(Participant::Payer, "ACSC", &[], "ACSC", &[]),
            Some(true)
        );
        assert_eq!(
            payer_outcome_match(
                Participant::Payer,
                "RJCT",
                &["AM04".to_owned()],
                "ACSC",
                &[],
            ),
            Some(false)
        );
    }
}
