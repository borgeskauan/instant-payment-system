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
