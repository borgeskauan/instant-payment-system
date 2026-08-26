use anyhow::{Result, bail};
use serde::Deserialize;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum NotificationPayload {
    Pacs008 {
        end_to_end_id: String,
    },
    Pacs002 {
        end_to_end_id: String,
        status: String,
        reason_codes: Vec<String>,
    },
}

pub fn parse_notifications(body: &[u8]) -> Result<Vec<NotificationPayload>> {
    let envelope: Envelope = serde_json::from_slice(body)?;
    let pacs008: Vec<_> = envelope
        .credit_transfers
        .into_iter()
        .filter_map(|transfer| transfer.payment_id.end_to_end_id())
        .map(|end_to_end_id| NotificationPayload::Pacs008 { end_to_end_id })
        .collect();
    if !pacs008.is_empty() {
        return Ok(pacs008);
    }

    let pacs002: Vec<_> = envelope
        .statuses
        .into_iter()
        .filter_map(|status| {
            let end_to_end_id = status.original_end_to_end_id()?;
            let reason_codes = status
                .reasons
                .into_iter()
                .map(|reason| reason.reason.code)
                .collect();
            Some(NotificationPayload::Pacs002 {
                end_to_end_id,
                status: status.status,
                reason_codes,
            })
        })
        .collect();
    if pacs002.is_empty() {
        bail!("notification payload does not contain a known transaction id");
    }
    Ok(pacs002)
}

#[derive(Deserialize)]
struct Envelope {
    #[serde(rename = "CdtTrfTxInf", default)]
    credit_transfers: Vec<CreditTransfer>,
    #[serde(rename = "TxInfAndSts", default)]
    statuses: Vec<PaymentStatus>,
}

#[derive(Deserialize)]
struct CreditTransfer {
    #[serde(rename = "PmtId")]
    payment_id: PaymentId,
}

#[derive(Deserialize)]
struct PaymentId {
    #[serde(rename = "EndToEndId", default)]
    end_to_end_id: String,
    #[serde(rename = "EndToEndID", default)]
    alternative_end_to_end_id: String,
}

impl PaymentId {
    fn end_to_end_id(self) -> Option<String> {
        if !self.end_to_end_id.is_empty() {
            Some(self.end_to_end_id)
        } else if !self.alternative_end_to_end_id.is_empty() {
            Some(self.alternative_end_to_end_id)
        } else {
            None
        }
    }
}

#[derive(Deserialize)]
struct PaymentStatus {
    #[serde(rename = "OrgnlEndToEndId", default)]
    original_end_to_end_id: String,
    #[serde(rename = "OrgnlEndToEndID", default)]
    alternative_original_end_to_end_id: String,
    #[serde(rename = "TxSts", default)]
    status: String,
    #[serde(rename = "StsRsnInf", default)]
    reasons: Vec<StatusReason>,
}

impl PaymentStatus {
    fn original_end_to_end_id(&self) -> Option<String> {
        if !self.original_end_to_end_id.is_empty() {
            Some(self.original_end_to_end_id.clone())
        } else if !self.alternative_original_end_to_end_id.is_empty() {
            Some(self.alternative_original_end_to_end_id.clone())
        } else {
            None
        }
    }
}

#[derive(Deserialize)]
struct StatusReason {
    #[serde(rename = "Rsn")]
    reason: ReasonCode,
}

#[derive(Deserialize)]
struct ReasonCode {
    #[serde(rename = "Cd", default)]
    code: String,
}
