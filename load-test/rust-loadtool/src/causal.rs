use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};

use anyhow::{Result, anyhow, bail};
use tokio::sync::{OwnedSemaphorePermit, Semaphore};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CausalKind {
    Original,
    Replay,
}

#[derive(Debug)]
pub struct CausalCapacity {
    semaphore: Arc<Semaphore>,
    current: Arc<AtomicU64>,
    maximum: Arc<AtomicU64>,
}

impl CausalCapacity {
    pub fn new(capacity: usize) -> Result<Self> {
        if capacity == 0 {
            bail!("generator causal HTTP capacity must be positive");
        }
        Ok(Self {
            semaphore: Arc::new(Semaphore::new(capacity)),
            current: Arc::new(AtomicU64::new(0)),
            maximum: Arc::new(AtomicU64::new(0)),
        })
    }

    pub fn try_acquire(&self, kind: CausalKind) -> Result<CausalPermit> {
        let permit = Arc::clone(&self.semaphore)
            .try_acquire_owned()
            .map_err(|_| anyhow!("generator causal HTTP capacity exhausted for {kind:?}"))?;
        let current = self.current.fetch_add(1, Ordering::AcqRel) + 1;
        self.maximum.fetch_max(current, Ordering::Relaxed);
        Ok(CausalPermit {
            _permit: permit,
            current: Arc::clone(&self.current),
        })
    }

    pub fn current(&self) -> u64 {
        self.current.load(Ordering::Acquire)
    }

    pub fn maximum(&self) -> u64 {
        self.maximum.load(Ordering::Acquire)
    }
}

#[derive(Debug)]
pub struct CausalPermit {
    _permit: OwnedSemaphorePermit,
    current: Arc<AtomicU64>,
}

impl Drop for CausalPermit {
    fn drop(&mut self) {
        self.current.fetch_sub(1, Ordering::AcqRel);
    }
}
