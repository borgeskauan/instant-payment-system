use std::fs;
use std::sync::Arc;
use std::time::{Duration, Instant, UNIX_EPOCH};

use loadtool_contract::event::{
    Event, MessageKind, NotificationKind, NotificationStatus, Participant,
};
use loadtool_contract::model::ExecutionPlan;
use loadtool_generator::clock::RunClock;
use loadtool_generator::planner::{Planner, RunIdentity};
use loadtool_generator::recorder::EventRecorder;

const PLAN: &str = r#"{
  "profile":"evidence-test","offeredTxRate":2100,"requiredMinimumTxRate":2000,
  "warmupBootstrapOfferedTxRate":500,"warmupBootstrapSeconds":1,"warmupBootstrapRequestTimeoutSeconds":30,
  "warmupSteadyOfferedTxRate":1500,"warmupSteadySeconds":1,"warmupSteadyRequestTimeoutSeconds":5,
  "warmupSeconds":2,"warmupCompletionTimeoutSeconds":120,"activeSeconds":1,"drainSeconds":30,
  "replay":{"pacs008":{"share":0.05,"delaySeconds":10},"pacs002":{"share":0.05,"delaySeconds":10}},
  "scenarios":[{"name":"happy-path","share":1.0,"participants":{"pairNumberStart":1,"hotPairCount":2,"coldPairCount":8,"hotTrafficShare":0.8},"amount":{"minimum":100,"maximum":200},"funding":{"payer":{"mode":"cover-generated-debits"},"receiver":{"mode":"fixed","balance":"0.00"},"resetIfExists":true},"provisioning":{"payerBalance":"1000.00","receiverBalance":"0.00","resetIfExists":true},"expectations":{"httpStatus":"2xx","payerNotification":{"deliverySemantics":"at-least-once","status":"ACSC","reasonCodes":[]}}}]
}"#;

fn recorder(temp: &tempfile::TempDir, capacity: usize) -> EventRecorder {
    let plan = Arc::new(ExecutionPlan::decode(PLAN.as_bytes()).expect("plan"));
    let planner = Arc::new(Planner::new(plan).expect("planner"));
    let clock = RunClock::new(Instant::now(), UNIX_EPOCH + Duration::from_secs(10));
    EventRecorder::start(
        temp.path(),
        planner,
        RunIdentity::new("rust-test"),
        clock,
        capacity,
    )
    .expect("recorder")
}

#[test]
fn recorder_writes_the_exact_persisted_csv_contract() {
    let temp = tempfile::tempdir().expect("temp dir");
    let recorder = recorder(&temp, 16);

    recorder
        .record(Event::Pacs008Completed {
            sequence: 0,
            created_offset_ns: 1,
            request_started_offset_ns: 2,
            request_done_offset_ns: 3,
            http_status: 200,
            replay_selected: true,
        })
        .unwrap();
    recorder
        .record(Event::Pacs002Completed {
            sequence: 0,
            request_started_offset_ns: 4,
            request_done_offset_ns: 5,
            http_status: 202,
            replay_selected: false,
        })
        .unwrap();
    recorder
        .record(Event::Notification {
            sequence: 0,
            participant: Participant::Payer,
            kind: NotificationKind::Pacs002Received,
            received_offset_ns: 6,
            status: NotificationStatus::Rjct,
            reason_codes: vec!["AM04".to_owned()],
        })
        .unwrap();
    recorder
        .record(Event::ReplayCompleted {
            sequence: 0,
            sender: Participant::Receiver,
            message: MessageKind::Pacs002,
            request_started_offset_ns: 7,
            request_done_offset_ns: 8,
            http_status: 200,
        })
        .unwrap();
    recorder.close().expect("close recorder");

    assert_eq!(
        fs::read_to_string(temp.path().join("pacs008-starts.csv")).unwrap(),
        concat!(
            "end_to_end_id,payer_ispb,receiver_ispb,created_at_ns,request_started_at_ns,request_done_at_ns,http_status,scenario_name,pacs008_replay_selected\n",
            "rust-test-0,10000003,20000003,10000000001,10000000002,10000000003,200,happy-path,true\n"
        )
    );
    assert_eq!(
        fs::read_to_string(temp.path().join("pacs002-starts.csv")).unwrap(),
        concat!(
            "end_to_end_id,sender_ispb,scenario_name,request_started_at_ns,request_done_at_ns,http_status,pacs002_replay_selected\n",
            "rust-test-0,20000003,happy-path,10000000004,10000000005,202,false\n"
        )
    );
    assert_eq!(
        fs::read_to_string(temp.path().join("notifications.csv")).unwrap(),
        concat!(
            "end_to_end_id,ispb,event_type,received_at_ns,status_code,reason_codes\n",
            "rust-test-0,10000003,pacs002_received,10000000006,RJCT,\"[\"\"AM04\"\"]\"\n"
        )
    );
    assert_eq!(
        fs::read_to_string(temp.path().join("replays.csv")).unwrap(),
        concat!(
            "end_to_end_id,sender_ispb,scenario_name,message_type,request_started_at_ns,request_done_at_ns,http_status\n",
            "rust-test-0,20000003,happy-path,pacs.002,10000000007,10000000008,200\n"
        )
    );
}

#[test]
fn recorder_never_silently_drops_events_when_its_bounded_queue_is_full() {
    let temp = tempfile::tempdir().expect("temp dir");
    let recorder = recorder(&temp, 1);
    let mut full = false;

    for sequence in 0..100_000 {
        let result = recorder.record(Event::Pacs008Completed {
            sequence: sequence % 1_000,
            created_offset_ns: sequence,
            request_started_offset_ns: sequence,
            request_done_offset_ns: sequence,
            http_status: 200,
            replay_selected: false,
        });
        if result.is_err() {
            full = true;
            break;
        }
    }

    assert!(full, "a saturated queue must be reported to its producer");
    assert!(
        recorder.close().is_err(),
        "queue saturation invalidates close"
    );
}

#[test]
fn recorder_rejects_preexisting_outputs() {
    let temp = tempfile::tempdir().expect("temp dir");
    fs::write(temp.path().join("notifications.csv"), "do not overwrite").unwrap();
    let plan = Arc::new(ExecutionPlan::decode(PLAN.as_bytes()).expect("plan"));
    let planner = Arc::new(Planner::new(plan).expect("planner"));

    let error = EventRecorder::start(
        temp.path(),
        planner,
        RunIdentity::new("rust-test"),
        RunClock::new(Instant::now(), UNIX_EPOCH),
        16,
    )
    .expect_err("existing evidence must not be replaced");

    assert!(error.to_string().contains("notifications.csv"));
    assert_eq!(
        fs::read_to_string(temp.path().join("notifications.csv")).unwrap(),
        "do not overwrite"
    );
}
