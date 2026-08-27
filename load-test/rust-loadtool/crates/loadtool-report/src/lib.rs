use std::fs::{self, OpenOptions};
use std::io::Write;

use anyhow::{Context, Result, anyhow};
use loadtool_contract::bundle::{Bundle, CompletedRun};
use loadtool_contract::generation_window::GenerationWindow;

pub mod generation;
mod outcome;
pub mod replay;
pub mod summary;

pub use summary::SlaReport;

pub fn build(completed: CompletedRun) -> Result<SlaReport> {
    summary::build(completed)
}

pub fn write(bundle: &Bundle, window: GenerationWindow) -> Result<()> {
    let completed = bundle.load_completed(window)?;
    let report = build(completed)?;
    let path = bundle.report();
    let parent = path.parent().context("report path has no parent")?;
    let temporary = parent.join(format!(
        ".{}.tmp",
        path.file_name()
            .context("report path has no file name")?
            .to_string_lossy()
    ));
    let mut file = OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(&temporary)
        .with_context(|| format!("create {}", temporary.display()))?;
    let result = (|| -> Result<()> {
        serde_json::to_writer_pretty(&mut file, &report)?;
        file.write_all(b"\n")?;
        file.sync_all()?;
        fs::hard_link(&temporary, path).map_err(|error| {
            if error.kind() == std::io::ErrorKind::AlreadyExists {
                anyhow!("sla-report.json already exists at {}", path.display())
            } else {
                anyhow!(error)
            }
        })?;
        fs::remove_file(&temporary)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}
