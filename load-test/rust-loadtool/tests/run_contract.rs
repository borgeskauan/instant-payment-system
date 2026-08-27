use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use anyhow::anyhow;
use loadtool_contract::generation_window::GenerationWindow;
use loadtool_generator::simulator::SimulationOptions;

#[tokio::test]
async fn generator_finishes_before_the_reporter_reads_the_bundle() {
    let run = prepared_run();
    let events = Arc::new(Mutex::new(Vec::new()));
    let generator_events = Arc::clone(&events);
    let reporter_events = Arc::clone(&events);

    rust_loadtool::run_with(
        run.path(),
        SimulationOptions::default(),
        move |bundle, _| async move {
            let _guard = CompletionGuard(Arc::clone(&generator_events));
            generator_events.lock().unwrap().push("generator");
            copy_generated_outputs(&fixture_root(), bundle.root());
            Ok(fixture_window())
        },
        move |bundle, window| {
            reporter_events.lock().unwrap().push("reporter");
            assert_eq!(window, fixture_window());
            loadtool_report::write(bundle, window)
        },
    )
    .await
    .unwrap();

    assert_eq!(
        *events.lock().unwrap(),
        ["generator", "generator-dropped", "reporter"]
    );
    assert!(run.path().join("sla-report.json").is_file());
}

#[tokio::test]
async fn generator_failure_skips_reporting_and_preserves_the_error() {
    let run = prepared_run();
    let reported = Arc::new(Mutex::new(false));
    let reporter_called = Arc::clone(&reported);

    let error = rust_loadtool::run_with(
        run.path(),
        SimulationOptions::default(),
        |_, _| async { Err(anyhow!("generator stopped")) },
        move |_, _| {
            *reporter_called.lock().unwrap() = true;
            unreachable!()
        },
    )
    .await
    .unwrap_err();

    assert!(format!("{error:#}").contains("generator stopped"));
    assert!(!*reported.lock().unwrap());
    assert!(!run.path().join("sla-report.json").exists());
}

#[tokio::test]
async fn generation_below_the_rolling_minimum_completes_and_preserves_the_facts() {
    let run = prepared_run();

    rust_loadtool::run_with(
        run.path(),
        SimulationOptions::default(),
        |bundle, _| async move {
            copy_generated_outputs(&fixture_root(), bundle.root());
            let starts = bundle.events_dir().join("pacs008-starts.csv");
            let contents = fs::read_to_string(&starts).unwrap();
            fs::write(
                starts,
                contents
                    .lines()
                    .filter(|line| {
                        !line.starts_with("fixture-2,") && !line.starts_with("fixture-3,")
                    })
                    .collect::<Vec<_>>()
                    .join("\n")
                    + "\n",
            )
            .unwrap();
            Ok(fixture_window())
        },
        loadtool_report::write,
    )
    .await
    .unwrap();

    let report: serde_json::Value =
        serde_json::from_slice(&fs::read(run.path().join("sla-report.json")).unwrap()).unwrap();
    assert!(report.get("valid").is_none());
    assert!(report.get("performance_qualified").is_none());
    assert_eq!(report["generation"]["required_minimum_tps"], 1);
    assert_eq!(report["generation"]["minimum_rolling_tps"], 0);
}

#[tokio::test]
async fn functional_violations_are_reported_without_failing_the_command() {
    let run = prepared_run();

    rust_loadtool::run_with(
        run.path(),
        SimulationOptions::default(),
        |bundle, _| async move {
            copy_generated_outputs(&fixture_root(), bundle.root());
            let starts = bundle.events_dir().join("pacs008-starts.csv");
            let contents = fs::read_to_string(&starts).unwrap();
            fs::write(
                starts,
                contents.replacen(",202,insufficient-funds", ",500,insufficient-funds", 1),
            )
            .unwrap();
            Ok(fixture_window())
        },
        loadtool_report::write,
    )
    .await
    .unwrap();

    let report: serde_json::Value =
        serde_json::from_slice(&fs::read(run.path().join("sla-report.json")).unwrap()).unwrap();
    assert_eq!(report["scenarios"][1]["violations"], 1);
}

#[tokio::test]
async fn reporter_failure_is_returned() {
    let run = prepared_run();
    let error = rust_loadtool::run_with(
        run.path(),
        SimulationOptions::default(),
        |bundle, _| async move {
            copy_generated_outputs(&fixture_root(), bundle.root());
            Ok(fixture_window())
        },
        |_, _| Err(anyhow!("reporter stopped")),
    )
    .await
    .unwrap_err();

    assert!(format!("{error:#}").contains("reporter stopped"));
}

#[tokio::test]
async fn existing_outputs_fail_before_the_generator_runs() {
    let run = prepared_run();
    fs::create_dir(run.path().join("events")).unwrap();
    let generated = Arc::new(Mutex::new(false));
    let generator_called = Arc::clone(&generated);

    let error = rust_loadtool::run_with(
        run.path(),
        SimulationOptions::default(),
        move |_, _| async move {
            *generator_called.lock().unwrap() = true;
            Ok(fixture_window())
        },
        |_, _| unreachable!(),
    )
    .await
    .unwrap_err();

    assert!(format!("{error:#}").contains("events already exists"));
    assert!(!*generated.lock().unwrap());
}

struct CompletionGuard(Arc<Mutex<Vec<&'static str>>>);

impl Drop for CompletionGuard {
    fn drop(&mut self) {
        self.0.lock().unwrap().push("generator-dropped");
    }
}

fn fixture_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../testdata/report-parity")
}

fn prepared_run() -> tempfile::TempDir {
    let temp = tempfile::tempdir().unwrap();
    copy_dir(&fixture_root().join("inputs"), &temp.path().join("inputs"));
    temp
}

fn copy_generated_outputs(source: &Path, destination: &Path) {
    copy_dir(&source.join("events"), &destination.join("events"));
}

fn fixture_window() -> GenerationWindow {
    GenerationWindow {
        generation_started_at_ns: 1_767_225_600_000_000_000,
        active_started_at_ns: 1_767_225_602_000_000_000,
        generation_ended_at_ns: 1_767_225_604_000_000_000,
        replay_deadline_at_ns: 1_767_225_614_000_000_000,
    }
}

fn copy_dir(source: &Path, destination: &Path) {
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
