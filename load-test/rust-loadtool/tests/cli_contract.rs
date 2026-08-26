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
fn simulate_requires_run_dir() {
    let output = run(&["simulate"]);

    assert_eq!(output.status.code(), Some(2));
    assert!(stderr(&output).contains("--run-dir"));
}

#[test]
fn unsupported_commands_and_extra_arguments_are_rejected() {
    for args in [
        vec!["report"],
        vec!["validate-profile"],
        vec!["simulate", "--run-dir", "/tmp/run", "extra"],
        vec!["simulate", "--run-dir", "/tmp/run", "--engine", "go"],
    ] {
        let output = run(&args);
        assert_eq!(output.status.code(), Some(2), "args={args:?}");
    }
}

#[test]
fn valid_simulate_shape_reaches_the_unimplemented_boundary() {
    let run_dir = tempfile::tempdir().expect("temporary run directory");
    let output = run(&[
        "simulate",
        "--run-dir",
        run_dir.path().to_str().expect("UTF-8 path"),
    ]);

    assert_eq!(output.status.code(), Some(1));
    assert!(stderr(&output).contains("simulation is not implemented"));
}
