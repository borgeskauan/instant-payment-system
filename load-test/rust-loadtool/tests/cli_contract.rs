use std::process::{Command, Output};

fn run(args: &[&str]) -> Output {
    Command::new(env!("CARGO_BIN_EXE_rust-loadtool"))
        .args(args)
        .output()
        .expect("run rust-loadtool")
}

fn stderr(output: &Output) -> String {
    String::from_utf8_lossy(&output.stderr).into_owned()
}

#[test]
fn no_arguments_prints_usage_and_exits_two() {
    let output = run(&[]);

    assert_eq!(output.status.code(), Some(2));
    assert!(stderr(&output).contains("Usage:"));
}

#[test]
fn run_requires_run_dir() {
    let output = run(&["run"]);

    assert_eq!(output.status.code(), Some(2));
    assert!(stderr(&output).contains("--run-dir"));
}

#[test]
fn unsupported_commands_and_extra_arguments_are_rejected() {
    for args in [
        vec!["report"],
        vec!["simulate", "--run-dir", "/tmp/run"],
        vec!["validate-profile", "extra"],
        vec!["run", "--run-dir", "/tmp/run", "extra"],
        vec!["run", "--run-dir", "/tmp/run", "--engine", "go"],
    ] {
        let output = run(&args);
        assert_eq!(output.status.code(), Some(2), "args={args:?}");
    }
}

#[test]
fn validate_profile_defaults_and_accepts_an_explicit_internal_name() {
    for args in [
        vec!["validate-profile"],
        vec!["validate-profile", "--profile", "mixed-outcomes-smoke"],
    ] {
        let output = run(&args);
        assert!(output.status.success(), "{}", stderr(&output));
        let document: serde_json::Value = serde_json::from_slice(&output.stdout).unwrap();
        let expected = if args.len() == 1 {
            "uniform-smoke"
        } else {
            "mixed-outcomes-smoke"
        };
        assert_eq!(document["profile"], expected);
    }
}

#[test]
fn validate_profile_rejects_invalid_and_unknown_names() {
    for name in ["../escape", "missing-profile"] {
        let output = run(&["validate-profile", "--profile", name]);
        assert_eq!(output.status.code(), Some(1));
        assert!(stderr(&output).contains(if name == "../escape" {
            "invalid profile name"
        } else {
            "not found"
        }));
    }
}

#[test]
fn valid_run_shape_validates_the_prepared_run() {
    let run_dir = tempfile::tempdir().expect("temporary run directory");
    let output = run(&[
        "run",
        "--run-dir",
        run_dir.path().to_str().expect("UTF-8 path"),
    ]);

    assert_eq!(output.status.code(), Some(1));
    assert!(stderr(&output).contains("required profile.json is missing"));
}
