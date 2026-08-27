use std::fs;

use loadtool_contract::bundle::Bundle;
use loadtool_contract::generation_window::GenerationWindow;

#[test]
fn report_deep_matches_the_frozen_contract_fixture() {
    let root = fixture_root();
    let completed = Bundle::resolve(&root)
        .unwrap()
        .load_completed(fixture_window())
        .unwrap();
    let actual = serde_json::to_value(loadtool_report::build(completed).unwrap()).unwrap();
    let expected: serde_json::Value =
        serde_json::from_slice(&fs::read(root.join("expected-sla-report.json")).unwrap()).unwrap();

    assert_eq!(actual, expected);
}

#[test]
fn incomplete_planned_generation_can_still_meet_the_rolling_minimum() {
    let temp = copy_fixture();
    rewrite(&temp.path().join("events/pacs008-starts.csv"), |contents| {
        contents
            .lines()
            .filter(|line| !line.starts_with("fixture-3,"))
            .collect::<Vec<_>>()
            .join("\n")
            + "\n"
    });

    let completed = Bundle::resolve(temp.path())
        .unwrap()
        .load_completed(fixture_window())
        .unwrap();
    let report = loadtool_report::build(completed).unwrap();
    assert_eq!(report.generation.planned_originals, 4);
    assert_eq!(report.generation.executed_originals, 3);
    assert!(
        report.generation.minimum_rolling_tps >= report.generation.required_minimum_tps as usize
    );
    assert!(report.performance.within_sla);
}

#[test]
fn latency_above_the_sla_is_not_a_correctness_failure() {
    let temp = copy_fixture();
    rewrite(&temp.path().join("events/notifications.csv"), |contents| {
        contents
            .replace("1767225602200000000", "1767225603900000000")
            .replace("1767225602250000000", "1767225603950000000")
    });

    let report = build_fixture(temp.path());
    assert!(!report.performance.within_sla);
}

#[test]
fn report_publication_is_atomic_and_never_overwrites() {
    let temp = copy_fixture();
    let bundle = Bundle::resolve(temp.path()).unwrap();
    loadtool_report::write(&bundle, fixture_window()).unwrap();
    let bytes = fs::read(temp.path().join("sla-report.json")).unwrap();
    assert_eq!(*bytes.last().unwrap(), b'\n');

    let error = loadtool_report::write(&bundle, fixture_window()).unwrap_err();
    assert!(format!("{error:#}").contains("already exists"));
}

#[test]
fn missing_or_contradictory_payer_outcomes_are_violations() {
    let missing = copy_fixture();
    rewrite(
        &missing.path().join("events/notifications.csv"),
        |contents| {
            contents
                .lines()
                .filter(|line| !line.starts_with("fixture-3,"))
                .collect::<Vec<_>>()
                .join("\n")
                + "\n"
        },
    );
    let report = build_fixture(missing.path());
    assert_eq!(report.scenarios[1].outcome.missing, 1);
    assert_eq!(report.scenarios[1].violations, 1);

    let contradictory = copy_fixture();
    rewrite(
        &contradictory.path().join("events/notifications.csv"),
        |mut contents| {
            contents.push_str("fixture-0,10000001,pacs002_received,1767225602260000000,RJCT,[]\n");
            contents
        },
    );
    let report = build_fixture(contradictory.path());
    assert_eq!(report.scenarios[0].outcome.matched, 2);
    assert_eq!(report.scenarios[0].outcome.contradictory, 1);
    assert_eq!(report.scenarios[0].violations, 1);
}

#[test]
fn ingress_and_aggregate_replay_failures_are_reported_as_violations() {
    let bad_http = copy_fixture();
    rewrite(
        &bad_http.path().join("events/pacs008-starts.csv"),
        |contents| contents.replacen(",202,insufficient-funds", ",500,insufficient-funds", 1),
    );
    assert_eq!(build_fixture(bad_http.path()).scenarios[1].violations, 1);

    let mutations: [fn(String) -> String; 3] = [
        |contents| {
            contents
                .lines()
                .filter(|line| !line.contains("pacs.008"))
                .collect::<Vec<_>>()
                .join("\n")
                + "\n"
        },
        |contents| {
            let row = contents
                .lines()
                .find(|line| line.contains("pacs.008"))
                .unwrap()
                .to_owned();
            format!("{contents}{row}\n")
        },
        |contents| contents.replacen("1767225603110000000,200", "1767225603110000000,500", 1),
    ];
    for mutate in mutations {
        let temp = copy_fixture();
        rewrite(&temp.path().join("events/replays.csv"), mutate);
        let report = build_fixture(temp.path());
        assert!(report.replays.pacs008.violations > 0);
    }
}

#[test]
fn replay_identity_and_timing_do_not_create_violations() {
    let replay = copy_fixture();
    rewrite(&replay.path().join("events/replays.csv"), |contents| {
        contents.replace(
            "fixture-0,10000001,happy-path,pacs.008,1767225603100000000",
            "unknown,99999999,unknown,pacs.008,1767225602999999999",
        )
    });
    assert_eq!(build_fixture(replay.path()).replays.pacs008.violations, 0);

    let late = copy_fixture();
    rewrite(&late.path().join("events/pacs002-starts.csv"), |contents| {
        contents.replace("1767225602100000000", "1767225604000000000")
    });
    assert_eq!(build_fixture(late.path()).replays.pacs002.violations, 0);
}

fn fixture_root() -> std::path::PathBuf {
    std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../testdata/report-parity")
}

fn copy_fixture() -> tempfile::TempDir {
    let temp = tempfile::tempdir().unwrap();
    copy_dir(&fixture_root(), temp.path());
    temp
}

fn build_fixture(root: &std::path::Path) -> loadtool_report::SlaReport {
    let completed = Bundle::resolve(root)
        .unwrap()
        .load_completed(fixture_window())
        .unwrap();
    loadtool_report::build(completed).unwrap()
}

fn fixture_window() -> GenerationWindow {
    GenerationWindow {
        generation_started_at_ns: 1_767_225_600_000_000_000,
        active_started_at_ns: 1_767_225_602_000_000_000,
        generation_ended_at_ns: 1_767_225_604_000_000_000,
        replay_deadline_at_ns: 1_767_225_614_000_000_000,
    }
}

fn rewrite(path: &std::path::Path, transform: impl FnOnce(String) -> String) {
    let contents = fs::read_to_string(path).unwrap();
    fs::write(path, transform(contents)).unwrap();
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
