use std::fs::{self, File, OpenOptions};
use std::io::BufWriter;
use std::path::{Path, PathBuf};
use std::sync::mpsc::{self, SyncSender, TrySendError};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};

use anyhow::{Context, Result, anyhow};

use crate::clock::RunClock;
use crate::histogram::DurationHistogram;
use crate::planner::{Planner, RunIdentity};
use loadtool_contract::event::{Event, Participant};
use loadtool_contract::generator_metrics::HistogramSummary;

const BUFFER_SIZE: usize = 4 * 1024 * 1024;

const PACS008_HEADER: &[&str] = &[
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
const PACS002_HEADER: &[&str] = &[
    "end_to_end_id",
    "sender_ispb",
    "scenario_name",
    "request_started_at_ns",
    "request_done_at_ns",
    "http_status",
    "pacs002_replay_selected",
];
const NOTIFICATION_HEADER: &[&str] = &[
    "end_to_end_id",
    "ispb",
    "event_type",
    "received_at_ns",
    "status_code",
    "reason_codes",
];
const REPLAY_HEADER: &[&str] = &[
    "end_to_end_id",
    "sender_ispb",
    "scenario_name",
    "message_type",
    "request_started_at_ns",
    "request_done_at_ns",
    "http_status",
];

#[derive(Debug)]
pub struct EventRecorder {
    sender: Option<SyncSender<Event>>,
    failure: Arc<Mutex<Option<String>>>,
    worker: Option<JoinHandle<Result<RecorderSummary>>>,
}

#[derive(Clone, Debug)]
pub struct EventSender {
    sender: SyncSender<Event>,
    failure: Arc<Mutex<Option<String>>>,
}

#[derive(Clone, Copy, Debug, Default)]
pub struct RecorderSummary {
    pub http_start_lateness: HistogramSummary,
}

impl EventRecorder {
    pub fn start(
        events_dir: &Path,
        planner: Arc<Planner>,
        identity: RunIdentity,
        clock: RunClock,
        capacity: usize,
    ) -> Result<Self> {
        if capacity == 0 {
            return Err(anyhow!("recorder queue capacity must be positive"));
        }
        fs::create_dir_all(events_dir)
            .with_context(|| format!("create events directory {}", events_dir.display()))?;
        let paths = OutputPaths::new(events_dir);
        paths.require_absent()?;
        let outputs = CsvOutputs::create(&paths)?;
        let (sender, receiver) = mpsc::sync_channel(capacity);
        let failure = Arc::new(Mutex::new(None));
        let worker_failure = Arc::clone(&failure);
        let worker = thread::Builder::new()
            .name("loadtool-recorder".to_owned())
            .spawn(move || {
                let result = record_loop(receiver, outputs, &planner, &identity, clock);
                if let Err(error) = &result {
                    set_failure(&worker_failure, error.to_string());
                }
                result
            })
            .context("start recorder thread")?;
        Ok(Self {
            sender: Some(sender),
            failure,
            worker: Some(worker),
        })
    }

    pub fn record(&self, event: Event) -> Result<()> {
        self.sender()?.record(event)
    }

    pub fn sender(&self) -> Result<EventSender> {
        Ok(EventSender {
            sender: self
                .sender
                .as_ref()
                .ok_or_else(|| anyhow!("recorder is closed"))?
                .clone(),
            failure: Arc::clone(&self.failure),
        })
    }

    pub fn close(mut self) -> Result<RecorderSummary> {
        self.sender.take();
        let worker_result = self
            .worker
            .take()
            .expect("recorder owns one worker")
            .join()
            .map_err(|_| anyhow!("event recorder thread panicked"))?;
        if let Some(error) = current_failure(&self.failure) {
            return Err(anyhow!(error));
        }
        worker_result
    }
}

impl EventSender {
    pub fn record(&self, event: Event) -> Result<()> {
        if let Some(error) = current_failure(&self.failure) {
            return Err(anyhow!(error));
        }
        match self.sender.try_send(event) {
            Ok(()) => Ok(()),
            Err(TrySendError::Full(_)) => {
                let error = "event recorder queue is full".to_owned();
                set_failure(&self.failure, error.clone());
                Err(anyhow!(error))
            }
            Err(TrySendError::Disconnected(_)) => {
                let error = current_failure(&self.failure)
                    .unwrap_or_else(|| "event recorder thread stopped".to_owned());
                set_failure(&self.failure, error.clone());
                Err(anyhow!(error))
            }
        }
    }
}

fn record_loop(
    receiver: mpsc::Receiver<Event>,
    mut outputs: CsvOutputs,
    planner: &Planner,
    identity: &RunIdentity,
    clock: RunClock,
) -> Result<RecorderSummary> {
    let mut http_start_lateness = DurationHistogram::new();
    for event in receiver {
        write_event(
            &mut outputs,
            planner,
            identity,
            clock,
            event,
            &mut http_start_lateness,
        )?;
    }
    outputs.finish()?;
    Ok(RecorderSummary {
        http_start_lateness: http_start_lateness.summary(),
    })
}

fn write_event(
    outputs: &mut CsvOutputs,
    planner: &Planner,
    identity: &RunIdentity,
    clock: RunClock,
    event: Event,
    http_start_lateness: &mut DurationHistogram,
) -> Result<()> {
    match event {
        Event::Pacs008Completed {
            sequence,
            created_offset_ns,
            request_started_offset_ns,
            request_done_offset_ns,
            http_status,
            replay_selected,
        } => {
            http_start_lateness
                .record_ns(request_started_offset_ns.saturating_sub(created_offset_ns));
            let payment = planner.payment(sequence)?;
            let (payer, receiver) = pair(payment.pair_number);
            outputs.pacs008.write_record([
                identity.end_to_end_id(sequence),
                payer,
                receiver,
                clock.unix_nanos_offset(created_offset_ns)?.to_string(),
                clock
                    .unix_nanos_offset(request_started_offset_ns)?
                    .to_string(),
                clock.unix_nanos_offset(request_done_offset_ns)?.to_string(),
                http_status.to_string(),
                payment.scenario_name.to_owned(),
                replay_selected.to_string(),
            ])?;
        }
        Event::Pacs002Completed {
            sequence,
            request_started_offset_ns,
            request_done_offset_ns,
            http_status,
            replay_selected,
        } => {
            let payment = planner.payment(sequence)?;
            let (_, receiver) = pair(payment.pair_number);
            outputs.pacs002.write_record([
                identity.end_to_end_id(sequence),
                receiver,
                payment.scenario_name.to_owned(),
                clock
                    .unix_nanos_offset(request_started_offset_ns)?
                    .to_string(),
                clock.unix_nanos_offset(request_done_offset_ns)?.to_string(),
                http_status.to_string(),
                replay_selected.to_string(),
            ])?;
        }
        Event::Notification {
            sequence,
            participant,
            kind,
            received_offset_ns,
            status,
            reason_codes,
        } => {
            let payment = planner.payment(sequence)?;
            let (payer, receiver) = pair(payment.pair_number);
            let ispb = participant_value(participant, payer, receiver);
            outputs.notifications.write_record([
                identity.end_to_end_id(sequence),
                ispb,
                kind.as_str().to_owned(),
                clock.unix_nanos_offset(received_offset_ns)?.to_string(),
                status.as_str().to_owned(),
                serde_json::to_string(&reason_codes)?,
            ])?;
        }
        Event::ReplayCompleted {
            sequence,
            sender,
            message,
            request_started_offset_ns,
            request_done_offset_ns,
            http_status,
        } => {
            let payment = planner.payment(sequence)?;
            let (payer, receiver) = pair(payment.pair_number);
            outputs.replays.write_record([
                identity.end_to_end_id(sequence),
                participant_value(sender, payer, receiver),
                payment.scenario_name.to_owned(),
                message.as_str().to_owned(),
                clock
                    .unix_nanos_offset(request_started_offset_ns)?
                    .to_string(),
                clock.unix_nanos_offset(request_done_offset_ns)?.to_string(),
                http_status.to_string(),
            ])?;
        }
    }
    Ok(())
}

fn pair(number: u32) -> (String, String) {
    (format!("10{number:06}"), format!("20{number:06}"))
}

fn participant_value(participant: Participant, payer: String, receiver: String) -> String {
    match participant {
        Participant::Payer => payer,
        Participant::Receiver => receiver,
    }
}

struct OutputPaths {
    pacs008: PathBuf,
    pacs002: PathBuf,
    notifications: PathBuf,
    replays: PathBuf,
}

impl OutputPaths {
    fn new(root: &Path) -> Self {
        Self {
            pacs008: root.join("pacs008-starts.csv"),
            pacs002: root.join("pacs002-starts.csv"),
            notifications: root.join("notifications.csv"),
            replays: root.join("replays.csv"),
        }
    }

    fn require_absent(&self) -> Result<()> {
        for path in [
            &self.pacs008,
            &self.pacs002,
            &self.notifications,
            &self.replays,
        ] {
            if path.exists() {
                return Err(anyhow!(
                    "evidence output already exists: {}",
                    path.display()
                ));
            }
        }
        Ok(())
    }
}

type CsvWriter = csv::Writer<BufWriter<File>>;

struct CsvOutputs {
    pacs008: CsvWriter,
    pacs002: CsvWriter,
    notifications: CsvWriter,
    replays: CsvWriter,
}

impl CsvOutputs {
    fn create(paths: &OutputPaths) -> Result<Self> {
        let mut outputs = Self {
            pacs008: create_csv(&paths.pacs008)?,
            pacs002: create_csv(&paths.pacs002)?,
            notifications: create_csv(&paths.notifications)?,
            replays: create_csv(&paths.replays)?,
        };
        outputs.pacs008.write_record(PACS008_HEADER)?;
        outputs.pacs002.write_record(PACS002_HEADER)?;
        outputs.notifications.write_record(NOTIFICATION_HEADER)?;
        outputs.replays.write_record(REPLAY_HEADER)?;
        Ok(outputs)
    }

    fn finish(&mut self) -> Result<()> {
        for writer in [
            &mut self.pacs008,
            &mut self.pacs002,
            &mut self.notifications,
            &mut self.replays,
        ] {
            writer.flush()?;
            writer.get_ref().get_ref().sync_all()?;
        }
        Ok(())
    }
}

fn create_csv(path: &Path) -> Result<CsvWriter> {
    let file = OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(path)
        .with_context(|| format!("create {}", path.display()))?;
    Ok(csv::Writer::from_writer(BufWriter::with_capacity(
        BUFFER_SIZE,
        file,
    )))
}

fn set_failure(failure: &Mutex<Option<String>>, error: String) {
    let mut current = failure
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    if current.is_none() {
        *current = Some(error);
    }
}

fn current_failure(failure: &Mutex<Option<String>>) -> Option<String> {
    failure
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone()
}
