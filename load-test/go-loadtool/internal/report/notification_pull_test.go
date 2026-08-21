package report

import (
	"encoding/json"
	"strings"
	"testing"

	"instant-payment-system/load-test/go-loadtool/internal/pullmetrics"
)

func TestSummarizeNotificationPullUsesOnlyNonEmptyBatchesForDistribution(t *testing.T) {
	snapshot := pullmetrics.Snapshot{EmptyResponses: 3}
	snapshot.Batches[1] = 1
	snapshot.Batches[2] = 2
	snapshot.Batches[10] = 1

	summary := summarizeNotificationPull(snapshot)

	if summary.Batches.Count != 4 || summary.Batches.EmptyResponses != 3 {
		t.Fatalf("notification pull metadata = %#v", summary)
	}
	if summary.Batches.Mean != 3.75 || summary.Batches.P50 != 2 || summary.Batches.P95 != 10 || summary.Batches.Max != 10 {
		t.Fatalf("notification pull distribution = %#v", summary.Batches)
	}
	if summary.Violations != 0 {
		t.Fatalf("violations = %d, want 0", summary.Violations)
	}
}

func TestSummarizeNotificationPullReportsObservedBatchAboveProtocolMaximum(t *testing.T) {
	snapshot := pullmetrics.Snapshot{AboveProtocolMaximum: true}

	if summary := summarizeNotificationPull(snapshot); summary.Violations != 1 {
		t.Fatalf("violations = %d, want 1", summary.Violations)
	}
}

func TestNotificationPullSummaryDoesNotExposeFixedProtocolLimitAsConfiguration(t *testing.T) {
	encoded, err := json.Marshal(NotificationPullSummary{})
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(encoded), "configured_max_batch") {
		t.Fatalf("notification pull summary exposes fixed protocol limit: %s", encoded)
	}
}
