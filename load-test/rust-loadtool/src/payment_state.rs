use std::sync::atomic::{AtomicU8, Ordering};

const COMMITTED: u8 = 1 << 0;
const PACS002_CLAIMED: u8 = 1 << 1;
const EXPECTED_OUTCOME_SEEN: u8 = 1 << 2;
const CONTRADICTION_SEEN: u8 = 1 << 3;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum OutcomeObservation {
    IgnoredUncommitted,
    MatchedFirst,
    MatchedAgain,
    ContradictionFirst,
    ContradictionAgain,
}

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

    pub fn observe_outcome(&self, sequence: u64, matches_expected: bool) -> OutcomeObservation {
        let Some(state) = self.state(sequence) else {
            return OutcomeObservation::IgnoredUncommitted;
        };
        if state.load(Ordering::Acquire) & COMMITTED == 0 {
            return OutcomeObservation::IgnoredUncommitted;
        }
        let (flag, first, repeated) = if matches_expected {
            (
                EXPECTED_OUTCOME_SEEN,
                OutcomeObservation::MatchedFirst,
                OutcomeObservation::MatchedAgain,
            )
        } else {
            (
                CONTRADICTION_SEEN,
                OutcomeObservation::ContradictionFirst,
                OutcomeObservation::ContradictionAgain,
            )
        };
        if state.fetch_or(flag, Ordering::AcqRel) & flag == 0 {
            first
        } else {
            repeated
        }
    }

    fn state(&self, sequence: u64) -> Option<&AtomicU8> {
        usize::try_from(sequence)
            .ok()
            .and_then(|index| self.states.get(index))
    }
}
