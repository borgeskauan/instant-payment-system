package report

import (
	"testing"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/events"
)

func TestSummaryCountsSLA(t *testing.T) {
	starts := []events.Start{
		{EndToEndID: "tx-1", PayerISPB: "10000001", CreatedAtNS: 0, HTTPStatus: 200},
		{EndToEndID: "tx-2", PayerISPB: "10000002", CreatedAtNS: 0, HTTPStatus: 200},
		{EndToEndID: "tx-3", PayerISPB: "10000003", CreatedAtNS: 0, HTTPStatus: 500},
	}
	notifications := []events.Notification{
		{EndToEndID: "tx-1", ISPB: "20000001", EventType: events.EventPacs002Received, ReceivedAtNS: 500_000_000},
		{EndToEndID: "tx-1", ISPB: "10000001", EventType: events.EventPacs002Received, ReceivedAtNS: 1_000_000_000},
		{EndToEndID: "tx-2", ISPB: "10000002", EventType: events.EventPacs002Received, ReceivedAtNS: 5_000_000_000},
	}

	summary := Build(starts, notifications, 4600)

	if summary.Transactions.Started != 3 {
		t.Fatalf("Started = %d, want 3", summary.Transactions.Started)
	}
	if summary.Transactions.Accepted != 2 {
		t.Fatalf("Accepted = %d, want 2", summary.Transactions.Accepted)
	}
	if summary.Transactions.Completion.Completed != 2 {
		t.Fatalf("Completed = %d, want 2", summary.Transactions.Completion.Completed)
	}
	if summary.Transactions.CompletedBySLA.AfterSLA != 1 {
		t.Fatalf("AfterSLA = %d, want 1", summary.Transactions.CompletedBySLA.AfterSLA)
	}
	if summary.Transactions.CompletedBySLA.WithinSLA != 1 {
		t.Fatalf("WithinSLA = %d, want 1", summary.Transactions.CompletedBySLA.WithinSLA)
	}
	if summary.Transactions.Completion.NotCompleted != 0 {
		t.Fatalf("NotCompleted = %d, want 0", summary.Transactions.Completion.NotCompleted)
	}
}

func TestSummaryCountsNeverConfirmed(t *testing.T) {
	starts := []events.Start{
		{EndToEndID: "tx-1", PayerISPB: "10000001", CreatedAtNS: 0, HTTPStatus: 200},
	}

	summary := Build(starts, nil, 4600)

	if summary.Transactions.Completion.NotCompleted != 1 {
		t.Fatalf("NotCompleted = %d, want 1", summary.Transactions.Completion.NotCompleted)
	}
}

func TestSummaryUsesEarliestPayerConfirmation(t *testing.T) {
	starts := []events.Start{
		{EndToEndID: "tx-1", PayerISPB: "10000001", CreatedAtNS: 0, HTTPStatus: 200},
	}
	notifications := []events.Notification{
		{EndToEndID: "tx-1", ISPB: "10000001", EventType: events.EventPacs002Received, ReceivedAtNS: 3_000_000_000},
		{EndToEndID: "tx-1", ISPB: "20000001", EventType: events.EventPacs002Received, ReceivedAtNS: 500_000_000},
		{EndToEndID: "tx-1", ISPB: "10000001", EventType: events.EventPacs002Received, ReceivedAtNS: 1_000_000_000},
	}

	summary := Build(starts, notifications, 4600)

	if summary.LatencyMs.P50 != 1000 {
		t.Fatalf("P50 = %f, want 1000", summary.LatencyMs.P50)
	}
}

func TestSummaryReportsActualStartRate(t *testing.T) {
	starts := []events.Start{
		{EndToEndID: "tx-1", PayerISPB: "10000001", CreatedAtNS: 0, HTTPStatus: 200},
		{EndToEndID: "tx-2", PayerISPB: "10000002", CreatedAtNS: 500_000_000, HTTPStatus: 200},
		{EndToEndID: "tx-3", PayerISPB: "10000003", CreatedAtNS: 1_000_000_000, HTTPStatus: 200},
	}

	summary := BuildWithOptions(starts, nil, Options{
		SLAThresholdMs: 4600,
		TargetTxRate:   2,
		Duration:       2 * time.Second,
	})

	if summary.ThroughputPerSecond.Started != 1.5 {
		t.Fatalf("Started throughput = %f, want 1.5", summary.ThroughputPerSecond.Started)
	}
	if summary.Windows.ActualStartWindowSeconds != 1 {
		t.Fatalf("ActualStartWindowSeconds = %f, want 1", summary.Windows.ActualStartWindowSeconds)
	}
	if summary.ThroughputPerSecond.StartedActualWindow != 3 {
		t.Fatalf("StartedActualWindow = %f, want 3", summary.ThroughputPerSecond.StartedActualWindow)
	}
	if summary.Windows.ConfiguredActiveSeconds != 2 {
		t.Fatalf("ConfiguredActiveSeconds = %f, want 2", summary.Windows.ConfiguredActiveSeconds)
	}
}

func TestSummaryReportsConfirmedRatesForActiveWindowAndDrain(t *testing.T) {
	starts := []events.Start{
		{EndToEndID: "tx-1", PayerISPB: "10000001", CreatedAtNS: 0, HTTPStatus: 200},
		{EndToEndID: "tx-2", PayerISPB: "10000002", CreatedAtNS: 0, HTTPStatus: 200},
		{EndToEndID: "tx-3", PayerISPB: "10000003", CreatedAtNS: 0, HTTPStatus: 200},
		{EndToEndID: "tx-4", PayerISPB: "10000004", CreatedAtNS: 0, HTTPStatus: 200},
	}
	notifications := []events.Notification{
		{EndToEndID: "tx-1", ISPB: "10000001", EventType: events.EventPacs002Received, ReceivedAtNS: 1_000_000},
		{EndToEndID: "tx-2", ISPB: "10000002", EventType: events.EventPacs002Received, ReceivedAtNS: 1_000_000},
		{EndToEndID: "tx-3", ISPB: "10000003", EventType: events.EventPacs002Received, ReceivedAtNS: 3_000_000_000},
	}

	summary := BuildWithOptions(starts, notifications, Options{
		SLAThresholdMs: 4600,
		Duration:       2 * time.Second,
	})

	if summary.ThroughputPerSecond.CompletedDuringActive != 1 {
		t.Fatalf("CompletedDuringActive = %f, want 1", summary.ThroughputPerSecond.CompletedDuringActive)
	}
	if summary.ThroughputPerSecond.CompletedIncludingDrain != 1.5 {
		t.Fatalf("CompletedIncludingDrain = %f, want 1.5", summary.ThroughputPerSecond.CompletedIncludingDrain)
	}
}
