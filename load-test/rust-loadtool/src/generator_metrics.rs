use hdrhistogram::Histogram;
use serde::Serialize;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HistogramSummary {
    pub count: u64,
    pub p50_ns: u64,
    pub p95_ns: u64,
    pub p99_ns: u64,
    pub max_ns: u64,
}

pub(crate) struct DurationHistogram {
    histogram: Histogram<u64>,
}

impl DurationHistogram {
    pub(crate) fn new() -> Self {
        Self {
            histogram: Histogram::new_with_bounds(1, 60_000_000_000, 3)
                .expect("fixed histogram bounds are valid"),
        }
    }

    pub(crate) fn record_ns(&mut self, value: u64) {
        self.histogram
            .record(value.max(1))
            .expect("duration is inside fixed histogram bounds");
    }

    pub(crate) fn summary(&self) -> HistogramSummary {
        if self.histogram.is_empty() {
            return HistogramSummary::default();
        }
        HistogramSummary {
            count: self.histogram.len(),
            p50_ns: self.histogram.value_at_quantile(0.50),
            p95_ns: self.histogram.value_at_quantile(0.95),
            p99_ns: self.histogram.value_at_quantile(0.99),
            max_ns: self.histogram.max(),
        }
    }
}
