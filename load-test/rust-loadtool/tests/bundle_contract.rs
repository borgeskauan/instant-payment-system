use std::fs;
use std::path::Path;

use loadtool_contract::bundle::Bundle;

const PROFILE: &str = r#"{
  "name": "contract-test",
  "connections": {
    "centralTransfer": {
      "baseUrl": "https://localhost:8001",
      "caCert": "ignored-ca",
      "clientCertRoot": "ignored-clients",
      "serverName": "localhost"
    },
    "notificationGateway": {
      "address": "localhost:9090",
      "caCert": "ignored-ca",
      "clientCertRoot": "ignored-clients",
      "serverName": "localhost"
    }
  },
  "reporting": {"slaThresholdMs": 1000},
  "load": {"ignored": true},
  "scenarios": []
}"#;

const EXECUTION_PLAN: &str = r#"{
  "profile": "contract-test",
  "offeredTxRate": 2100,
  "requiredMinimumTxRate": 2000,
  "warmupBootstrapOfferedTxRate": 500,
  "warmupBootstrapSeconds": 1,
  "warmupBootstrapRequestTimeoutSeconds": 30,
  "warmupSteadyOfferedTxRate": 1500,
  "warmupSteadySeconds": 2,
  "warmupSteadyRequestTimeoutSeconds": 5,
  "warmupSeconds": 3,
  "warmupCompletionTimeoutSeconds": 120,
  "activeSeconds": 4,
  "drainSeconds": 30,
  "replay": {
    "pacs008": {"share": 0.05, "delaySeconds": 10},
    "pacs002": {"share": 0.05, "delaySeconds": 10}
  },
  "scenarios": [{
    "name": "happy-path",
    "share": 1.0,
    "participants": {
      "pairNumberStart": 1,
      "hotPairCount": 2,
      "coldPairCount": 8,
      "hotTrafficShare": 0.8
    },
    "amount": {"minimum": 100, "maximum": 200},
    "funding": {
      "payer": {"mode": "cover-generated-debits"},
      "receiver": {"mode": "fixed", "balance": "0.00"},
      "resetIfExists": true
    },
    "provisioning": {
      "payerBalance": "1000.00",
      "receiverBalance": "0.00",
      "resetIfExists": true
    },
    "expectations": {
      "httpStatus": "2xx",
      "payerNotification": {
        "deliverySemantics": "at-least-once",
        "status": "ACSC",
        "reasonCodes": []
      }
    }
  }]
}"#;

fn prepared_run() -> tempfile::TempDir {
    let temp = tempfile::tempdir().expect("temporary run directory");
    let inputs = temp.path().join("inputs");
    fs::create_dir(&inputs).expect("create inputs");
    fs::write(inputs.join("profile.json"), PROFILE).expect("write profile");
    fs::write(inputs.join("execution-plan.json"), EXECUTION_PLAN).expect("write plan");
    temp
}

#[test]
fn resolves_fixed_layout_and_decodes_the_normalized_plan() {
    let temp = prepared_run();
    let bundle = Bundle::resolve(temp.path()).expect("resolve bundle");
    let prepared = bundle.load_prepared().expect("load prepared bundle");

    assert!(bundle.root().is_absolute());
    assert_eq!(bundle.events_dir(), bundle.root().join("events"));
    assert_eq!(prepared.profile.name, "contract-test");
    assert_eq!(prepared.plan.profile, prepared.profile.name);
    assert_eq!(prepared.plan.load.offered_tx_rate, 2100);
    assert_eq!(prepared.plan.load.warmup.bootstrap.offered_tx_rate, 500);
    assert_eq!(prepared.plan.load.warmup.steady.duration.as_secs(), 2);
    assert_eq!(prepared.plan.scenarios[0].participants.pair_number_start, 1);
    assert_eq!(prepared.plan.maximum_planned_slots().unwrap(), 11_900);
}

#[test]
fn profile_name_must_match_the_execution_plan() {
    let temp = prepared_run();
    let profile_path = temp.path().join("inputs/profile.json");
    fs::write(
        profile_path,
        PROFILE.replace("contract-test", "another-profile"),
    )
    .expect("replace profile");

    let error = Bundle::resolve(temp.path())
        .and_then(|bundle| bundle.load_prepared())
        .expect_err("mismatched profile must fail");

    assert!(error.to_string().contains("does not match"));
}

#[test]
fn malformed_missing_and_unknown_plan_inputs_fail_before_outputs_exist() {
    let unknown = EXECUTION_PLAN.replace("\n}", ",\n  \"unknown\": true\n}");
    for replacement in ["{", unknown.as_str()] {
        let temp = prepared_run();
        fs::write(temp.path().join("inputs/execution-plan.json"), replacement)
            .expect("replace plan");
        let bundle = Bundle::resolve(temp.path()).expect("resolve bundle");

        assert!(bundle.load_prepared().is_err());
        assert!(!bundle.events_dir().exists());
    }

    let temp = prepared_run();
    fs::remove_file(temp.path().join("inputs/profile.json")).expect("remove profile");
    assert!(
        Bundle::resolve(temp.path())
            .unwrap()
            .load_prepared()
            .is_err()
    );
}

#[test]
fn generated_outputs_must_be_absent_and_are_created_by_rust() {
    for relative in ["events", "sla-report.json"] {
        let temp = prepared_run();
        let path = temp.path().join(relative);
        if Path::new(relative).extension().is_none() {
            fs::create_dir(&path).expect("create generated directory");
        } else {
            fs::write(&path, "existing").expect("create generated file");
        }

        let bundle = Bundle::resolve(temp.path()).expect("resolve bundle");
        assert!(bundle.load_prepared().is_err(), "relative={relative}");
    }

    let temp = prepared_run();
    let bundle = Bundle::resolve(temp.path()).expect("resolve bundle");
    bundle.load_prepared().expect("validate bundle");
    bundle.prepare_outputs().expect("prepare Rust outputs");

    assert!(bundle.events_dir().is_dir());
    assert!(!bundle.root().join("run-window.json").exists());
    assert!(!bundle.root().join("generator-metrics.json").exists());
}
