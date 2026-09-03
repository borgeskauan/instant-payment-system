#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Participant {
    Payer,
    Receiver,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MessageKind {
    Pacs008,
    Pacs002,
}

impl MessageKind {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Pacs008 => "pacs.008",
            Self::Pacs002 => "pacs.002",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum NotificationKind {
    Pacs008Received,
    Pacs002Received,
    Pacs002Sent,
}

impl NotificationKind {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Pacs008Received => "pacs008_received",
            Self::Pacs002Received => "pacs002_received",
            Self::Pacs002Sent => "pacs002_sent",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum NotificationStatus {
    None,
    Acsc,
    Rjct,
    Other(String),
}

impl NotificationStatus {
    pub fn as_str(&self) -> &str {
        match self {
            Self::None => "",
            Self::Acsc => "ACSC",
            Self::Rjct => "RJCT",
            Self::Other(value) => value,
        }
    }
}

#[derive(Debug)]
pub enum Event {
    Pacs008Completed {
        sequence: u64,
        created_offset_ns: u64,
        request_started_offset_ns: u64,
        request_done_offset_ns: u64,
        http_status: u16,
        replay_selected: bool,
    },
    Pacs002Completed {
        sequence: u64,
        request_started_offset_ns: u64,
        request_done_offset_ns: u64,
        http_status: u16,
        replay_selected: bool,
    },
    Notification {
        sequence: u64,
        participant: Participant,
        kind: NotificationKind,
        received_offset_ns: u64,
        status: NotificationStatus,
        reason_codes: Vec<String>,
    },
    ReplayCompleted {
        sequence: u64,
        sender: Participant,
        message: MessageKind,
        request_started_offset_ns: u64,
        request_done_offset_ns: u64,
        http_status: u16,
    },
}
use std::path::Path;

use anyhow::{Context, Result, anyhow};
use csv::StringRecord;

const PACS008_HEADER: [&str; 9] = [
    "end_to_end_id",
    "payer_ispb",
    "receiver_ispb",
    "created_at_ns",
    "request_started_at_ns",
    "request_done_at_ns",
    "http_status",
    "scenario_name",
    "pacs008_replay_selected",
];
const PACS002_HEADER: [&str; 7] = [
    "end_to_end_id",
    "sender_ispb",
    "scenario_name",
    "request_started_at_ns",
    "request_done_at_ns",
    "http_status",
    "pacs002_replay_selected",
];
const NOTIFICATION_HEADER: [&str; 6] = [
    "end_to_end_id",
    "ispb",
    "event_type",
    "received_at_ns",
    "status_code",
    "reason_codes",
];
const REPLAY_HEADER: [&str; 7] = [
    "end_to_end_id",
    "sender_ispb",
    "scenario_name",
    "message_type",
    "request_started_at_ns",
    "request_done_at_ns",
    "http_status",
];

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Pacs008Start {
    pub end_to_end_id: String,
    pub payer_ispb: String,
    pub receiver_ispb: String,
    pub created_at_ns: i64,
    pub request_started_at_ns: i64,
    pub request_done_at_ns: i64,
    pub http_status: u16,
    pub scenario_name: String,
    pub replay_selected: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Pacs002Start {
    pub end_to_end_id: String,
    pub sender_ispb: String,
    pub scenario_name: String,
    pub request_started_at_ns: i64,
    pub request_done_at_ns: i64,
    pub http_status: u16,
    pub replay_selected: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Notification {
    pub end_to_end_id: String,
    pub ispb: String,
    pub event_type: String,
    pub received_at_ns: i64,
    pub status_code: String,
    pub reason_codes: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Replay {
    pub end_to_end_id: String,
    pub sender_ispb: String,
    pub scenario_name: String,
    pub message_type: String,
    pub request_started_at_ns: i64,
    pub request_done_at_ns: i64,
    pub http_status: u16,
}

#[derive(Debug)]
pub struct RunEvents {
    pub pacs008: Vec<Pacs008Start>,
    pub pacs002: Vec<Pacs002Start>,
    pub notifications: Vec<Notification>,
    pub replays: Vec<Replay>,
}

pub fn read_pacs008_starts(path: &Path) -> Result<Vec<Pacs008Start>> {
    read_records(path, &PACS008_HEADER, |record| {
        Ok(Pacs008Start {
            end_to_end_id: field(record, 0)?.to_owned(),
            payer_ispb: field(record, 1)?.to_owned(),
            receiver_ispb: field(record, 2)?.to_owned(),
            created_at_ns: parse(record, 3, "created_at_ns")?,
            request_started_at_ns: parse(record, 4, "request_started_at_ns")?,
            request_done_at_ns: parse(record, 5, "request_done_at_ns")?,
            http_status: parse(record, 6, "http_status")?,
            scenario_name: field(record, 7)?.to_owned(),
            replay_selected: parse(record, 8, "pacs008_replay_selected")?,
        })
    })
}

pub fn read_pacs002_starts(path: &Path) -> Result<Vec<Pacs002Start>> {
    read_records(path, &PACS002_HEADER, |record| {
        Ok(Pacs002Start {
            end_to_end_id: field(record, 0)?.to_owned(),
            sender_ispb: field(record, 1)?.to_owned(),
            scenario_name: field(record, 2)?.to_owned(),
            request_started_at_ns: parse(record, 3, "request_started_at_ns")?,
            request_done_at_ns: parse(record, 4, "request_done_at_ns")?,
            http_status: parse(record, 5, "http_status")?,
            replay_selected: parse(record, 6, "pacs002_replay_selected")?,
        })
    })
}

pub fn read_notifications(path: &Path) -> Result<Vec<Notification>> {
    read_records(path, &NOTIFICATION_HEADER, |record| {
        let raw = field(record, 5)?;
        let value: serde_json::Value = serde_json::from_str(raw)
            .with_context(|| format!("parse notification reason_codes {raw:?}"))?;
        if !value.is_array() {
            return Err(anyhow!("notification reason_codes must be a JSON array"));
        }
        let reason_codes = serde_json::from_value(value)
            .context("notification reason_codes must contain only strings")?;
        Ok(Notification {
            end_to_end_id: field(record, 0)?.to_owned(),
            ispb: field(record, 1)?.to_owned(),
            event_type: field(record, 2)?.to_owned(),
            received_at_ns: parse(record, 3, "received_at_ns")?,
            status_code: field(record, 4)?.to_owned(),
            reason_codes,
        })
    })
}

pub fn read_replays(path: &Path) -> Result<Vec<Replay>> {
    read_records(path, &REPLAY_HEADER, |record| {
        Ok(Replay {
            end_to_end_id: field(record, 0)?.to_owned(),
            sender_ispb: field(record, 1)?.to_owned(),
            scenario_name: field(record, 2)?.to_owned(),
            message_type: field(record, 3)?.to_owned(),
            request_started_at_ns: parse(record, 4, "request_started_at_ns")?,
            request_done_at_ns: parse(record, 5, "request_done_at_ns")?,
            http_status: parse(record, 6, "http_status")?,
        })
    })
}

fn read_records<T>(
    path: &Path,
    expected_header: &[&str],
    mut decode: impl FnMut(&StringRecord) -> Result<T>,
) -> Result<Vec<T>> {
    let mut reader =
        csv::Reader::from_path(path).with_context(|| format!("open {}", path.display()))?;
    let header = reader
        .headers()
        .with_context(|| format!("read {} header", path.display()))?;
    if header.iter().ne(expected_header.iter().copied()) {
        return Err(anyhow!(
            "{} header is {:?}, want {:?}",
            path.display(),
            header,
            expected_header
        ));
    }
    let mut rows = Vec::new();
    for (index, record) in reader.records().enumerate() {
        let record =
            record.with_context(|| format!("read {} row {}", path.display(), index + 2))?;
        if record.len() != expected_header.len() {
            return Err(anyhow!(
                "{} row {} has {} columns, want {}",
                path.display(),
                index + 2,
                record.len(),
                expected_header.len()
            ));
        }
        rows.push(
            decode(&record)
                .with_context(|| format!("decode {} row {}", path.display(), index + 2))?,
        );
    }
    Ok(rows)
}

fn field(record: &StringRecord, index: usize) -> Result<&str> {
    record
        .get(index)
        .ok_or_else(|| anyhow!("record is missing column {index}"))
}

fn parse<T>(record: &StringRecord, index: usize, name: &str) -> Result<T>
where
    T: std::str::FromStr,
    T::Err: std::fmt::Display,
{
    let value = field(record, index)?;
    value
        .parse()
        .map_err(|error| anyhow!("invalid {name} {value:?}: {error}"))
}
