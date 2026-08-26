use std::sync::Mutex;
use std::time::Instant;

use anyhow::{Result, anyhow, bail};
use tokio::sync::Notify;
use tokio::time::timeout_at;

#[derive(Debug, Default)]
struct State {
    pending: u64,
    generation_closed: bool,
    failure: Option<String>,
}

#[derive(Debug, Default)]
pub struct PhaseTracker {
    state: Mutex<State>,
    changed: Notify,
}

impl PhaseTracker {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn add(&self) -> Result<()> {
        let mut state = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if let Some(error) = &state.failure {
            return Err(anyhow!(error.clone()));
        }
        if state.generation_closed && state.pending == 0 {
            bail!("warmup phase has already completed");
        }
        state.pending = state
            .pending
            .checked_add(1)
            .ok_or_else(|| anyhow!("warmup pending work overflows"))?;
        Ok(())
    }

    pub fn done(&self) -> Result<()> {
        let mut state = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if state.pending == 0 {
            bail!("warmup work completed more than once");
        }
        state.pending -= 1;
        let completed = state.generation_closed && state.pending == 0;
        drop(state);
        if completed {
            self.changed.notify_waiters();
        }
        Ok(())
    }

    pub fn fail(&self, error: impl Into<String>) {
        let mut state = self.state.lock().unwrap_or_else(|value| value.into_inner());
        if state.failure.is_none() {
            state.failure = Some(error.into());
        }
        drop(state);
        self.changed.notify_waiters();
    }

    pub fn close_generation(&self) {
        let mut state = self.state.lock().unwrap_or_else(|error| error.into_inner());
        state.generation_closed = true;
        drop(state);
        self.changed.notify_waiters();
    }

    pub fn pending(&self) -> u64 {
        self.state
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .pending
    }

    pub async fn wait(&self, deadline: Instant) -> Result<()> {
        loop {
            let changed = self.changed.notified();
            {
                let state = self.state.lock().unwrap_or_else(|error| error.into_inner());
                if let Some(error) = &state.failure {
                    return Err(anyhow!(error.clone()));
                }
                if state.generation_closed && state.pending == 0 {
                    return Ok(());
                }
            }
            timeout_at(deadline.into(), changed).await.map_err(|_| {
                anyhow!(
                    "warmup completion deadline reached with {} obligations pending",
                    self.pending()
                )
            })?;
        }
    }
}
