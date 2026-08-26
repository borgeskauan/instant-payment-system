use anyhow::Result;
use bytes::Bytes;
use serde::Serialize;

pub fn pacs008(
    end_to_end_id: &str,
    payer_ispb: &str,
    receiver_ispb: &str,
    amount_cents: i64,
    created_at: &str,
) -> Result<Bytes> {
    let message_id = format!("MSG-{end_to_end_id}");
    let payload = Pacs008 {
        group_header: GroupHeader {
            message_id: &message_id,
            created_at,
            transaction_count: 1,
        },
        transfers: [CreditTransfer {
            payment_id: PaymentId { end_to_end_id },
            amount: SettlementAmount {
                value: amount_cents as f64 / 100.0,
                currency: "BRL",
            },
            debtor: Party {
                name: "Load Test Payer",
                id: PartyId {
                    private_id: PrivateId {
                        other: TextOtherId { id: "12345678900" },
                    },
                },
            },
            debtor_account: Account {
                id: AccountId {
                    other: NumericOtherId {
                        id: 987_654,
                        issuer: 1_234,
                    },
                },
                kind: AccountType { code: "CACC" },
                proxy: None,
            },
            debtor_agent: Agent::new(payer_ispb),
            creditor_agent: Agent::new(receiver_ispb),
            creditor: Party {
                name: "Load Test Receiver",
                id: PartyId {
                    private_id: PrivateId {
                        other: TextOtherId { id: "98765432100" },
                    },
                },
            },
            creditor_account: Account {
                id: AccountId {
                    other: NumericOtherId {
                        id: 123_456,
                        issuer: 5_678,
                    },
                },
                kind: AccountType { code: "CACC" },
                proxy: Some(Proxy {
                    id: "+5511999999999",
                }),
            },
            remittance: Remittance {
                unstructured: "Load test payment",
            },
        }],
    };
    serialize(&payload, 768)
}

pub fn pacs002(end_to_end_id: &str, created_at: &str) -> Result<Bytes> {
    let message_id = format!("STATUS-{end_to_end_id}");
    let payload = Pacs002 {
        group_header: GroupHeader {
            message_id: &message_id,
            created_at,
            transaction_count: 1,
        },
        statuses: [PaymentStatus {
            original_end_to_end_id: end_to_end_id,
            transaction_status: "ACSP",
        }],
    };
    serialize(&payload, 256)
}

fn serialize<T: Serialize>(value: &T, capacity: usize) -> Result<Bytes> {
    let mut buffer = Vec::with_capacity(capacity);
    serde_json::to_writer(&mut buffer, value)?;
    Ok(Bytes::from(buffer))
}

#[derive(Serialize)]
struct Pacs008<'a> {
    #[serde(rename = "GrpHdr")]
    group_header: GroupHeader<'a>,
    #[serde(rename = "CdtTrfTxInf")]
    transfers: [CreditTransfer<'a>; 1],
}

#[derive(Serialize)]
struct Pacs002<'a> {
    #[serde(rename = "GrpHdr")]
    group_header: GroupHeader<'a>,
    #[serde(rename = "TxInfAndSts")]
    statuses: [PaymentStatus<'a>; 1],
}

#[derive(Serialize)]
struct GroupHeader<'a> {
    #[serde(rename = "MsgId")]
    message_id: &'a str,
    #[serde(rename = "CreDtTm")]
    created_at: &'a str,
    #[serde(rename = "NbOfTxs")]
    transaction_count: u8,
}

#[derive(Serialize)]
struct CreditTransfer<'a> {
    #[serde(rename = "PmtId")]
    payment_id: PaymentId<'a>,
    #[serde(rename = "IntrBkSttlmAmt")]
    amount: SettlementAmount<'a>,
    #[serde(rename = "Dbtr")]
    debtor: Party<'a>,
    #[serde(rename = "DbtrAcct")]
    debtor_account: Account<'a>,
    #[serde(rename = "DbtrAgt")]
    debtor_agent: Agent<'a>,
    #[serde(rename = "CdtrAgt")]
    creditor_agent: Agent<'a>,
    #[serde(rename = "Cdtr")]
    creditor: Party<'a>,
    #[serde(rename = "CdtrAcct")]
    creditor_account: Account<'a>,
    #[serde(rename = "RmtInf")]
    remittance: Remittance<'a>,
}

#[derive(Serialize)]
struct PaymentId<'a> {
    #[serde(rename = "EndToEndId")]
    end_to_end_id: &'a str,
}

#[derive(Serialize)]
struct SettlementAmount<'a> {
    value: f64,
    #[serde(rename = "Ccy")]
    currency: &'a str,
}

#[derive(Serialize)]
struct Party<'a> {
    #[serde(rename = "Nm")]
    name: &'a str,
    #[serde(rename = "Id")]
    id: PartyId<'a>,
}

#[derive(Serialize)]
struct PartyId<'a> {
    #[serde(rename = "PrvtId")]
    private_id: PrivateId<'a>,
}

#[derive(Serialize)]
struct PrivateId<'a> {
    #[serde(rename = "Othr")]
    other: TextOtherId<'a>,
}

#[derive(Serialize)]
struct TextOtherId<'a> {
    #[serde(rename = "Id")]
    id: &'a str,
}

#[derive(Serialize)]
struct Account<'a> {
    #[serde(rename = "Id")]
    id: AccountId,
    #[serde(rename = "Tp")]
    kind: AccountType<'a>,
    #[serde(rename = "Prxy", skip_serializing_if = "Option::is_none")]
    proxy: Option<Proxy<'a>>,
}

#[derive(Serialize)]
struct AccountId {
    #[serde(rename = "Othr")]
    other: NumericOtherId,
}

#[derive(Serialize)]
struct NumericOtherId {
    #[serde(rename = "Id")]
    id: u32,
    #[serde(rename = "Issr")]
    issuer: u32,
}

#[derive(Serialize)]
struct AccountType<'a> {
    #[serde(rename = "Cd")]
    code: &'a str,
}

#[derive(Serialize)]
struct Proxy<'a> {
    #[serde(rename = "Id")]
    id: &'a str,
}

#[derive(Serialize)]
struct Agent<'a> {
    #[serde(rename = "FinInstnId")]
    financial_institution_id: FinancialInstitutionId<'a>,
}

impl<'a> Agent<'a> {
    fn new(ispb: &'a str) -> Self {
        Self {
            financial_institution_id: FinancialInstitutionId {
                clearing_member_id: ClearingMemberId { member_id: ispb },
            },
        }
    }
}

#[derive(Serialize)]
struct FinancialInstitutionId<'a> {
    #[serde(rename = "ClrSysMmbId")]
    clearing_member_id: ClearingMemberId<'a>,
}

#[derive(Serialize)]
struct ClearingMemberId<'a> {
    #[serde(rename = "MmbId")]
    member_id: &'a str,
}

#[derive(Serialize)]
struct Remittance<'a> {
    #[serde(rename = "Ustrd")]
    unstructured: &'a str,
}

#[derive(Serialize)]
struct PaymentStatus<'a> {
    #[serde(rename = "OrgnlEndToEndId")]
    original_end_to_end_id: &'a str,
    #[serde(rename = "TxSts")]
    transaction_status: &'a str,
}
