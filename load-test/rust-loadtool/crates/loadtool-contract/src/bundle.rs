use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result, anyhow};

use crate::event::{
    RunEvents, read_notifications, read_pacs002_starts, read_pacs008_starts, read_replays,
};
use crate::generation_window::GenerationWindow;
use crate::model::{ExecutionPlan, ProfileSnapshot};

#[derive(Debug)]
pub struct PreparedRun {
    pub profile: ProfileSnapshot,
    pub plan: ExecutionPlan,
}

#[derive(Debug)]
pub struct CompletedRun {
    pub profile: ProfileSnapshot,
    pub plan: ExecutionPlan,
    pub window: GenerationWindow,
    pub events: RunEvents,
}

#[derive(Clone, Debug)]
pub struct Bundle {
    root: PathBuf,
    profile: PathBuf,
    execution_plan: PathBuf,
    events_dir: PathBuf,
    report: PathBuf,
}

impl Bundle {
    pub fn resolve(run_dir: &Path) -> Result<Self> {
        if run_dir.as_os_str().is_empty() {
            return Err(anyhow!("run directory is required"));
        }
        let root = fs::canonicalize(run_dir)
            .with_context(|| format!("resolve run directory {}", run_dir.display()))?;
        let inputs = root.join("inputs");
        Ok(Self {
            profile: inputs.join("profile.json"),
            execution_plan: inputs.join("execution-plan.json"),
            events_dir: root.join("events"),
            report: root.join("sla-report.json"),
            root,
        })
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    pub fn events_dir(&self) -> &Path {
        &self.events_dir
    }

    pub fn report(&self) -> &Path {
        &self.report
    }

    pub fn load_prepared(&self) -> Result<PreparedRun> {
        self.validate_prepared()?;
        self.load_inputs()
    }

    pub fn load_completed(&self, window: GenerationWindow) -> Result<CompletedRun> {
        require_regular_file(&self.profile, "profile.json")?;
        require_regular_file(&self.execution_plan, "execution-plan.json")?;
        require_directory(&self.events_dir, "events")?;
        require_absent(&self.report, "sla-report.json")?;

        let prepared = self.load_inputs()?;
        window.validate(&prepared.plan)?;
        let events = RunEvents {
            pacs008: read_pacs008_starts(&self.events_dir.join("pacs008-starts.csv"))?,
            pacs002: read_pacs002_starts(&self.events_dir.join("pacs002-starts.csv"))?,
            notifications: read_notifications(&self.events_dir.join("notifications.csv"))?,
            replays: read_replays(&self.events_dir.join("replays.csv"))?,
        };
        Ok(CompletedRun {
            profile: prepared.profile,
            plan: prepared.plan,
            window,
            events,
        })
    }

    fn load_inputs(&self) -> Result<PreparedRun> {
        let profile_data =
            fs::read(&self.profile).with_context(|| format!("read {}", self.profile.display()))?;
        let profile: ProfileSnapshot = serde_json::from_slice(&profile_data)
            .with_context(|| format!("decode {}", self.profile.display()))?;
        let plan_data = fs::read(&self.execution_plan)
            .with_context(|| format!("read {}", self.execution_plan.display()))?;
        let plan = ExecutionPlan::decode(&plan_data)
            .with_context(|| format!("decode {}", self.execution_plan.display()))?;
        if profile.name != plan.profile {
            return Err(anyhow!(
                "profile snapshot name {:?} does not match execution plan {:?}",
                profile.name,
                plan.profile
            ));
        }
        Ok(PreparedRun { profile, plan })
    }

    pub fn prepare_outputs(&self) -> Result<()> {
        self.validate_prepared()?;
        fs::create_dir(&self.events_dir)
            .with_context(|| format!("create {}", self.events_dir.display()))?;
        Ok(())
    }

    fn validate_prepared(&self) -> Result<()> {
        require_regular_file(&self.profile, "profile.json")?;
        require_regular_file(&self.execution_plan, "execution-plan.json")?;
        require_absent(&self.events_dir, "events")?;
        require_absent(&self.report, "sla-report.json")?;
        Ok(())
    }
}

fn require_regular_file(path: &Path, name: &str) -> Result<()> {
    let metadata = fs::metadata(path)
        .with_context(|| format!("required {name} is missing at {}", path.display()))?;
    if !metadata.is_file() {
        return Err(anyhow!(
            "required {name} is not a regular file at {}",
            path.display()
        ));
    }
    Ok(())
}

fn require_directory(path: &Path, name: &str) -> Result<()> {
    let metadata = fs::metadata(path)
        .with_context(|| format!("required {name} is missing at {}", path.display()))?;
    if !metadata.is_dir() {
        return Err(anyhow!(
            "required {name} is not a directory at {}",
            path.display()
        ));
    }
    Ok(())
}

fn require_absent(path: &Path, name: &str) -> Result<()> {
    match fs::symlink_metadata(path) {
        Ok(_) => Err(anyhow!(
            "generated output {name} already exists at {}",
            path.display()
        )),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error).with_context(|| format!("inspect {}", path.display())),
    }
}
