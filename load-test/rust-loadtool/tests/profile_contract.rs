use std::fs;
use std::process::Command;

const PROFILES: [&str; 5] = [
    "uniform-smoke",
    "mixed-outcomes-smoke",
    "mixed-outcomes-2k-diagnostic",
    "mixed-outcomes-2k-6m",
    "mixed-outcomes-2k-15m",
];

#[test]
fn every_checked_in_profile_normalizes_identically_to_go() {
    let profiles = profiles_dir();
    for name in PROFILES {
        let plan = rust_loadtool::profile::compile(&profiles, name).unwrap();
        let rust: serde_json::Value =
            serde_json::from_slice(&plan.encode_pretty().unwrap()).unwrap();
        let output = Command::new("go")
            .args([
                "run",
                "./cmd/go-loadtool",
                "validate-profile",
                "--profile",
                name,
            ])
            .current_dir(profiles.join("../go-loadtool"))
            .env("GOCACHE", "/tmp/go-build-cache")
            .env("GOPATH", "/tmp/go")
            .output()
            .unwrap();
        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
        let go: serde_json::Value = serde_json::from_slice(&output.stdout).unwrap();
        assert_eq!(rust, go, "normalized profile {name}");
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
fn invalid_rates_durations_shares_funding_and_expectations_are_rejected() {
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
