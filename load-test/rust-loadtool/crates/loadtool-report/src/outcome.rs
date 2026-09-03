use std::collections::HashMap;

use loadtool_contract::event::Notification;
use loadtool_contract::model::PayerNotification;

pub(crate) type NotificationKey = (String, String);

#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct PayerMatch {
    pub matched: bool,
    pub status_mismatch: bool,
    pub reason_codes_mismatch: bool,
    pub earliest_matching_at_ns: i64,
}

pub(crate) fn collect(
    notifications: &[Notification],
) -> HashMap<NotificationKey, Vec<&Notification>> {
    let mut observations = HashMap::new();
    for notification in notifications {
        if notification.event_type != "pacs002_received" {
            continue;
        }
        observations
            .entry((
                notification.end_to_end_id.clone(),
                notification.ispb.clone(),
            ))
            .or_insert_with(Vec::new)
            .push(notification);
    }
    observations
}

pub(crate) fn match_payer_notifications(
    deliveries: Option<&Vec<&Notification>>,
    expectation: &PayerNotification,
) -> PayerMatch {
    let Some(deliveries) = deliveries else {
        return PayerMatch::default();
    };
    let mut result = PayerMatch::default();
    for delivery in deliveries {
        let status_matches = delivery.status_code == expectation.status;
        let reasons_match = equal_reason_codes(&delivery.reason_codes, &expectation.reason_codes);
        result.status_mismatch |= !status_matches;
        result.reason_codes_mismatch |= !reasons_match;
        if status_matches
            && reasons_match
            && (!result.matched || delivery.received_at_ns < result.earliest_matching_at_ns)
        {
            result.matched = true;
            result.earliest_matching_at_ns = delivery.received_at_ns;
        }
    }
    result
}

fn equal_reason_codes(left: &[String], right: &[String]) -> bool {
    if left.len() != right.len() {
        return false;
    }
    let mut left = left.to_vec();
    let mut right = right.to_vec();
    left.sort_unstable();
    right.sort_unstable();
    left == right
}
