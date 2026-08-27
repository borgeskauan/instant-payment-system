use std::fs;

use loadtool_contract::bundle::Bundle;
use loadtool_contract::event::{
    Pacs008Start, read_notifications, read_pacs002_starts, read_pacs008_starts, read_replays,
};
use loadtool_contract::generation_window::GenerationWindow;
use loadtool_report::generation;

#[test]
fn strict_csv_readers_accept_the_checked_in_fixture() {
    let root = fixture_root();
    let events = root.join("events");

    let pacs008 = read_pacs008_starts(&events.join("pacs008-starts.csv")).unwrap();
    let pacs002 = read_pacs002_starts(&events.join("pacs002-starts.csv")).unwrap();
    let notifications = read_notifications(&events.join("notifications.csv")).unwrap();
    let replays = read_replays(&events.join("replays.csv")).unwrap();

    assert_eq!(pacs008.len(), 4);
    assert_eq!(pacs002.len(), 2);
    assert_eq!(notifications.len(), 7);
    assert_eq!(replays.len(), 2);
    assert_eq!(notifications[4].reason_codes, ["AM04"]);
}

#[test]
fn rolling_windows_find_unaligned_gaps_and_do_not_credit_later_spikes() {
    let window = GenerationWindow {
        generation_started_at_ns: 0,
        active_started_at_ns: 1_000_000_000,
        generation_ended_at_ns: 3_000_000_000,
        replay_deadline_at_ns: 4_000_000_000,
    };
    let timestamps = [
        1_000_000_000,
        1_100_000_000,
        1_200_000_000,
        2_199_999_999,
        2_200_000_000,
        2_200_000_001,
    ];
    let starts: Vec<_> = timestamps
        .into_iter()
        .enumerate()
        .map(|(index, timestamp)| start(index, timestamp))
        .collect();

    let summary = generation::summarize(&starts, &window, 3, 3);
    assert_eq!(summary.planned_originals, 6);
    assert_eq!(summary.executed_originals, 6);
    assert_eq!(summary.required_minimum_tps, 3);
    assert_eq!(summary.minimum_rolling_tps, 1);
    assert!(!summary.valid);
}

fn start(index: usize, timestamp: i64) -> Pacs008Start {
    Pacs008Start {
        end_to_end_id: format!("id-{index}"),
        payer_ispb: "payer".to_owned(),
        receiver_ispb: "receiver".to_owned(),
        created_at_ns: timestamp,
        request_started_at_ns: timestamp,
        request_done_at_ns: timestamp,
        http_status: 200,
        scenario_name: "happy-path".to_owned(),
        replay_selected: false,
    }
}

#[test]
fn strict_csv_readers_reject_schema_and_value_drift() {
    let temp = tempfile::tempdir().unwrap();
    let bad_header = temp.path().join("bad-header.csv");
    fs::write(&bad_header, "end_to_end_id,payer_ispb,extra\na,b,c\n").unwrap();
    assert!(
        read_pacs008_starts(&bad_header)
            .unwrap_err()
            .to_string()
            .contains("header")
    );

    let bad_bool = temp.path().join("bad-bool.csv");
    fs::write(
        &bad_bool,
        concat!(
            "end_to_end_id,payer_ispb,receiver_ispb,created_at_ns,request_started_at_ns,request_done_at_ns,http_status,scenario_name,pacs008_replay_selected\n",
            "id,payer,receiver,1,2,3,200,happy-path,perhaps\n"
        ),
    )
    .unwrap();
    assert!(read_pacs008_starts(&bad_bool).is_err());

    let null_reasons = temp.path().join("null-reasons.csv");
    fs::write(
        &null_reasons,
        concat!(
            "end_to_end_id,ispb,event_type,received_at_ns,status_code,reason_codes\n",
            "id,payer,pacs002_received,1,ACSC,null\n"
        ),
    )
    .unwrap();
    let error = read_notifications(&null_reasons).unwrap_err();
    assert!(format!("{error:#}").contains("JSON array"));
}

#[test]
fn completed_bundle_requires_every_artifact_and_matching_profile() {
    let bundle = Bundle::resolve(&fixture_root()).unwrap();
    let completed = bundle
        .load_completed(fixture_window())
        .expect("valid completed fixture");
    assert_eq!(completed.plan.profile, "report-parity");
    assert_eq!(completed.events.pacs008.len(), 4);

    let temp = copy_fixture();
    fs::remove_file(temp.path().join("events/replays.csv")).unwrap();
    let error = Bundle::resolve(temp.path())
        .unwrap()
        .load_completed(fixture_window())
        .unwrap_err();
    assert!(error.to_string().contains("replays.csv"));
}

fn fixture_window() -> GenerationWindow {
    GenerationWindow {
        generation_started_at_ns: 1_767_225_600_000_000_000,
        active_started_at_ns: 1_767_225_601_000_000_000,
        generation_ended_at_ns: 1_767_225_603_000_000_000,
        replay_deadline_at_ns: 1_767_225_613_000_000_000,
    }
}

fn fixture_root() -> std::path::PathBuf {
    std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../testdata/report-parity")
}

fn copy_fixture() -> tempfile::TempDir {
    let temp = tempfile::tempdir().unwrap();
    copy_dir(&fixture_root(), temp.path());
    temp
}

fn copy_dir(source: &std::path::Path, destination: &std::path::Path) {
    fs::create_dir_all(destination).unwrap();
    for entry in fs::read_dir(source).unwrap() {
        let entry = entry.unwrap();
        let target = destination.join(entry.file_name());
        if entry.file_type().unwrap().is_dir() {
            copy_dir(&entry.path(), &target);
        } else {
            fs::copy(entry.path(), target).unwrap();
        }
    }
}
