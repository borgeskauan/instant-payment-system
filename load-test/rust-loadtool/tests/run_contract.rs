use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use anyhow::anyhow;
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
            Ok(())
        },
        move |bundle| {
            reporter_events.lock().unwrap().push("reporter");
            loadtool_report::write(bundle).map(|_| ())
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
        move |_| {
            *reporter_called.lock().unwrap() = true;
            Ok(())
        },
    )
    .await
    .unwrap_err();

    assert!(format!("{error:#}").contains("generator stopped"));
    assert!(!*reported.lock().unwrap());
    assert!(!run.path().join("sla-report.json").exists());
}

#[tokio::test]
async fn invalid_generation_still_produces_an_invalid_report() {
    let run = prepared_run();

    rust_loadtool::run_with(
        run.path(),
        SimulationOptions::default(),
        |bundle, _| async move {
            copy_generated_outputs(&fixture_root(), bundle.root());
            let metrics = bundle.generator_metrics();
            let mut value: serde_json::Value =
                serde_json::from_slice(&fs::read(metrics).unwrap()).unwrap();
            value["valid"] = false.into();
            value["violations"] = serde_json::json!(["fixture generator violation"]);
            fs::write(metrics, serde_json::to_vec_pretty(&value).unwrap()).unwrap();
            Ok(())
        },
        |bundle| loadtool_report::write(bundle).map(|_| ()),
    )
    .await
    .unwrap();

    let report: serde_json::Value =
        serde_json::from_slice(&fs::read(run.path().join("sla-report.json")).unwrap()).unwrap();
    assert_eq!(report["valid"], false);
}

#[tokio::test]
async fn reporter_failure_is_returned() {
    let run = prepared_run();
    let error = rust_loadtool::run_with(
        run.path(),
        SimulationOptions::default(),
        |bundle, _| async move {
            copy_generated_outputs(&fixture_root(), bundle.root());
            Ok(())
        },
        |_| Err(anyhow!("reporter stopped")),
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
            Ok(())
        },
        |_| Ok(()),
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
    copy_dir(
        &source.join("diagnostics"),
        &destination.join("diagnostics"),
    );
    fs::copy(
        source.join("run-window.json"),
        destination.join("run-window.json"),
    )
    .unwrap();
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
