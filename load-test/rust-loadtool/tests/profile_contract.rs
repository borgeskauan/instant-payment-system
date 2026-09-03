use std::fs;

use loadtool_contract::model::ExecutionPlan;

const PROFILES: [&str; 6] = [
    "uniform-smoke",
    "mixed-outcomes-smoke",
    "mixed-outcomes-2k-diagnostic",
    "mixed-outcomes-2k-6m",
    "mixed-outcomes-2k-15m",
    "mixed-outcomes-4k-diagnostic",
];

#[test]
fn four_k_diagnostic_preserves_the_official_workload_shape() {
    let plan =
        rust_loadtool::profile::compile(&profiles_dir(), "mixed-outcomes-4k-diagnostic").unwrap();

    assert_eq!(plan.profile, "mixed-outcomes-4k-diagnostic");
    assert_eq!(plan.load.offered_tx_rate, 4000);
    assert_eq!(plan.load.required_minimum_tx_rate, 4000);
    assert_eq!(plan.load.active_duration.as_secs(), 180);
    assert_eq!(plan.load.drain.as_secs(), 30);
    assert_eq!(plan.scenarios.len(), 2);
    assert_eq!(plan.scenarios[0].share, 0.8);
    assert_eq!(plan.scenarios[1].share, 0.2);
    assert_eq!(plan.replay.pacs008.as_ref().unwrap().share, 0.05);
    assert_eq!(plan.replay.pacs002.as_ref().unwrap().share, 0.05);
}

#[test]
fn every_checked_in_profile_compiles_to_its_stable_execution_shape() {
    let profiles = profiles_dir();
    let expected = [
        ("uniform-smoke", 2000, 2000, 60, 60, 30, 1, "131497920.00"),
        ("mixed-outcomes-smoke", 105, 100, 5, 10, 20, 2, "10400.00"),
        (
            "mixed-outcomes-2k-diagnostic",
            2100,
            2000,
            120,
            60,
            30,
            2,
            "155294092.80",
        ),
        (
            "mixed-outcomes-2k-6m",
            2100,
            2000,
            120,
            360,
            30,
            2,
            "561160089.60",
        ),
        (
            "mixed-outcomes-2k-15m",
            2100,
            2000,
            120,
            900,
            30,
            2,
            "1283118849.60",
        ),
        (
            "mixed-outcomes-4k-diagnostic",
            4000,
            4000,
            120,
            180,
            30,
            2,
            "522578112.00",
        ),
    ];
    assert_eq!(PROFILES, expected.map(|entry| entry.0));

    for (name, offered, required, warmup, active, drain, scenarios, payer_balance) in expected {
        let plan = rust_loadtool::profile::compile(&profiles, name).unwrap();
        assert_eq!(plan.profile, name);
        assert_eq!(plan.load.offered_tx_rate, offered);
        assert_eq!(plan.load.required_minimum_tx_rate, required);
        assert_eq!(
            plan.load.warmup.bootstrap.duration.as_secs()
                + plan.load.warmup.steady.duration.as_secs(),
            warmup
        );
        assert_eq!(plan.load.active_duration.as_secs(), active);
        assert_eq!(plan.load.drain.as_secs(), drain);
        assert_eq!(plan.scenarios.len(), scenarios);
        assert_eq!(plan.scenarios[0].participants.pair_number_start, 1);
        assert_eq!(plan.scenarios[0].provisioning.payer_balance, payer_balance);

        let encoded = plan.encode_pretty().unwrap();
        let decoded = ExecutionPlan::decode(&encoded).unwrap();
        assert_eq!(decoded.profile, plan.profile);
        assert_eq!(
            decoded.maximum_planned_slots().unwrap(),
            plan.maximum_planned_slots().unwrap()
        );
    }
}

#[test]
fn names_are_internal_and_cannot_escape_the_profiles_directory() {
    for name in [
        "",
        "Uppercase",
        "-leading",
        "under_score",
        "../escape",
        "nested/profile",
    ] {
        let error = rust_loadtool::profile::compile(&profiles_dir(), name).unwrap_err();
        assert!(
            format!("{error:#}").contains("invalid profile name"),
            "{name:?}: {error:#}"
        );
    }
    let error = rust_loadtool::profile::compile(&profiles_dir(), "missing-profile").unwrap_err();
    assert!(format!("{error:#}").contains("not found"));
}

#[test]
fn source_contract_is_strict_and_assigns_consecutive_participant_ranges() {
    let temp = tempfile::tempdir().unwrap();
    let source = fs::read_to_string(profiles_dir().join("mixed-outcomes-smoke.json")).unwrap();
    fs::write(
        temp.path().join("valid.json"),
        source.replace("mixed-outcomes-smoke", "valid"),
    )
    .unwrap();
    let plan = rust_loadtool::profile::compile(temp.path(), "valid").unwrap();
    assert_eq!(plan.scenarios[0].participants.pair_number_start, 1);
    assert_eq!(plan.scenarios[1].participants.pair_number_start, 41);

    fs::write(
        temp.path().join("extra.json"),
        r#"{"name":"extra","unexpected":true}"#,
    )
    .unwrap();
    assert!(rust_loadtool::profile::compile(temp.path(), "extra").is_err());
    fs::write(temp.path().join("broken.json"), "{not-json}").unwrap();
    assert!(rust_loadtool::profile::compile(temp.path(), "broken").is_err());
}

#[test]
fn invalid_profile_values_are_rejected() {
    let original = fs::read_to_string(profiles_dir().join("uniform-smoke.json")).unwrap();
    let cases = [
        (
            "zero-rate",
            original.replace("\"offeredTxRate\": 2000", "\"offeredTxRate\": 0"),
        ),
        (
            "fractional-duration",
            original.replace("\"duration\": \"1m\"", "\"duration\": \"1500ms\""),
        ),
        (
            "bad-share",
            original.replace("\"share\": 1.0", "\"share\": 0.333"),
        ),
        (
            "inexact-hot-traffic-share",
            original.replace("\"hotTrafficShare\": 0.8", "\"hotTrafficShare\": 0.735"),
        ),
        (
            "bad-funding",
            original.replace(
                "\"mode\": \"cover-generated-debits\"",
                "\"mode\": \"mystery\"",
            ),
        ),
        (
            "bad-status",
            original.replace("\"status\": \"ACSC\"", "\"status\": \"accepted\""),
        ),
        (
            "empty-server-name",
            original.replacen("\"serverName\": \"localhost\"", "\"serverName\": \"\"", 1),
        ),
        (
            "missing-server-name",
            original.replacen(",\n      \"serverName\": \"localhost\"", "", 1),
        ),
    ];
    let temp = tempfile::tempdir().unwrap();
    for (name, contents) in cases {
        fs::write(
            temp.path().join(format!("{name}.json")),
            contents.replace("uniform-smoke", name),
        )
        .unwrap();
        assert!(
            rust_loadtool::profile::compile(temp.path(), name).is_err(),
            "{name}"
        );
    }
}

fn profiles_dir() -> std::path::PathBuf {
    std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../profiles")
}
