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

	if summary.Started != 3 {
		t.Fatalf("Started = %d, want 3", summary.Started)
	}
	if summary.Accepted != 2 {
		t.Fatalf("Accepted = %d, want 2", summary.Accepted)
	}
	if summary.Confirmed != 2 {
		t.Fatalf("Confirmed = %d, want 2", summary.Confirmed)
	}
	if summary.MissedSLA != 1 {
		t.Fatalf("MissedSLA = %d, want 1", summary.MissedSLA)
	}
	if summary.NeverConfirmed != 0 {
		t.Fatalf("NeverConfirmed = %d, want 0", summary.NeverConfirmed)
	}
}

func TestSummaryCountsNeverConfirmed(t *testing.T) {
	starts := []events.Start{
		{EndToEndID: "tx-1", PayerISPB: "10000001", CreatedAtNS: 0, HTTPStatus: 200},
	}

	summary := Build(starts, nil, 4600)

	if summary.NeverConfirmed != 1 {
		t.Fatalf("NeverConfirmed = %d, want 1", summary.NeverConfirmed)
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

	if summary.P50Ms != 1000 {
		t.Fatalf("P50Ms = %f, want 1000", summary.P50Ms)
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

	if summary.ActualStartedPerConfiguredSecond != 1.5 {
		t.Fatalf("ActualStartedPerConfiguredSecond = %f, want 1.5", summary.ActualStartedPerConfiguredSecond)
	}
	if summary.StartWindowSeconds != 1 {
		t.Fatalf("StartWindowSeconds = %f, want 1", summary.StartWindowSeconds)
	}
	if summary.ActualStartedPerStartWindowSecond != 3 {
		t.Fatalf("ActualStartedPerStartWindowSecond = %f, want 3", summary.ActualStartedPerStartWindowSecond)
	}
	if summary.StartRateAchievementPct != 75 {
		t.Fatalf("StartRateAchievementPct = %f, want 75", summary.StartRateAchievementPct)
	}
}
