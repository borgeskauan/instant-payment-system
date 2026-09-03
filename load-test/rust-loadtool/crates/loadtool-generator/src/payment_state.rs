use std::sync::atomic::{AtomicU8, Ordering};

const COMMITTED: u8 = 1 << 0;
const PACS002_CLAIMED: u8 = 1 << 1;

#[derive(Debug)]
pub struct PaymentStates {
    states: Vec<AtomicU8>,
}

impl PaymentStates {
    pub fn new(slots: usize) -> Self {
        Self {
            states: (0..slots).map(|_| AtomicU8::new(0)).collect(),
        }
    }

    pub fn commit(&self, sequence: u64) -> bool {
        let Some(state) = self.state(sequence) else {
            return false;
        };
        state.fetch_or(COMMITTED, Ordering::AcqRel) & COMMITTED == 0
    }

    pub fn is_committed(&self, sequence: u64) -> bool {
        self.state(sequence)
            .is_some_and(|state| state.load(Ordering::Acquire) & COMMITTED != 0)
    }

    pub fn claim_pacs002(&self, sequence: u64) -> bool {
        let Some(state) = self.state(sequence) else {
            return false;
        };
        if state.load(Ordering::Acquire) & COMMITTED == 0 {
            return false;
        }
        state.fetch_or(PACS002_CLAIMED, Ordering::AcqRel) & PACS002_CLAIMED == 0
    }

    fn state(&self, sequence: u64) -> Option<&AtomicU8> {
        usize::try_from(sequence)
            .ok()
            .and_then(|index| self.states.get(index))
    }
}
