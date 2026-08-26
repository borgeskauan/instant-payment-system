use std::fs;

use loadtool_contract::bundle::Bundle;

#[test]
fn report_deep_matches_the_frozen_contract_fixture() {
    let root = fixture_root();
    let completed = Bundle::resolve(&root).unwrap().load_completed().unwrap();
    let actual = serde_json::to_value(loadtool_report::build(completed).unwrap()).unwrap();
    let expected: serde_json::Value =
        serde_json::from_slice(&fs::read(root.join("expected-sla-report.json")).unwrap()).unwrap();

    assert_eq!(actual, expected);
}

#[test]
fn invalid_generation_forces_the_final_report_invalid() {
    let temp = copy_fixture();
    let metrics = temp
        .path()
        .join("diagnostics/loadtool/generator-metrics.json");
    let value = fs::read_to_string(&metrics)
        .unwrap()
        .replace("\"valid\": true", "\"valid\": false");
    fs::write(metrics, value).unwrap();

    let completed = Bundle::resolve(temp.path())
        .unwrap()
        .load_completed()
        .unwrap();
    let report = loadtool_report::build(completed).unwrap();
    assert!(!report.valid);
    assert!(report.generation.sustained_minimum_met);
    assert!(report.performance.within_sla);
}

#[test]
fn report_publication_is_atomic_and_never_overwrites() {
    let temp = copy_fixture();
    let bundle = Bundle::resolve(temp.path()).unwrap();
    let report = loadtool_report::write(&bundle).unwrap();
    assert!(report.valid);
    let bytes = fs::read(temp.path().join("sla-report.json")).unwrap();
    assert_eq!(*bytes.last().unwrap(), b'\n');

    let error = loadtool_report::write(&bundle).unwrap_err();
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
    assert!(!report.valid);

    let contradictory = copy_fixture();
    rewrite(
        &contradictory.path().join("events/notifications.csv"),
        |mut contents| {
            contents.push_str("fixture-0,10000001,pacs002_received,1767225601260000000,RJCT,[]\n");
            contents
        },
    );
    let report = build_fixture(contradictory.path());
    assert_eq!(report.scenarios[0].outcome.matched, 2);
    assert_eq!(report.scenarios[0].outcome.contradictory, 1);
    assert!(!report.valid);
}

#[test]
fn ingress_and_replay_defects_are_reported_as_violations() {
    let bad_http = copy_fixture();
    rewrite(
        &bad_http.path().join("events/pacs008-starts.csv"),
        |contents| contents.replacen(",202,insufficient-funds", ",500,insufficient-funds", 1),
    );
    assert!(!build_fixture(bad_http.path()).valid);

    let mutations: [fn(String) -> String; 3] = [
        |contents| {
            contents
                .lines()
                .filter(|line| !line.contains("pacs.008"))
                .collect::<Vec<_>>()
                .join("\n")
                + "\n"
        },
        |contents| contents.replace("1767225602100000000", "1767225601999999999"),
        |contents| {
            let row = contents
                .lines()
                .find(|line| line.contains("pacs.008"))
                .unwrap()
                .to_owned();
            format!("{contents}{row}\n")
        },
    ];
    for mutate in mutations {
        let temp = copy_fixture();
        rewrite(&temp.path().join("events/replays.csv"), mutate);
        let report = build_fixture(temp.path());
        assert!(report.replays.pacs008.violations > 0);
        assert!(!report.valid);
    }
}

#[test]
fn late_pacs002_and_pull_protocol_violations_invalidate_the_run() {
    let late = copy_fixture();
    rewrite(&late.path().join("events/pacs002-starts.csv"), |contents| {
        contents.replace("1767225601100000000", "1767225603000000000")
    });
    assert!(build_fixture(late.path()).replays.pacs002.violations > 0);

    let pull = copy_fixture();
    let metrics = pull
        .path()
        .join("diagnostics/loadtool/generator-metrics.json");
    let mut document: serde_json::Value =
        serde_json::from_slice(&fs::read(&metrics).unwrap()).unwrap();
    document["valid"] = false.into();
    document["violations"] =
        serde_json::json!(["notification Pull returned 16 messages above protocol maximum"]);
    fs::write(&metrics, serde_json::to_vec_pretty(&document).unwrap()).unwrap();
    let report = build_fixture(pull.path());
    assert_eq!(report.notification_pull.violations, 1);
    assert!(!report.valid);
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
    let completed = Bundle::resolve(root).unwrap().load_completed().unwrap();
    loadtool_report::build(completed).unwrap()
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
