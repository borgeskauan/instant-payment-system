package report

import (
	"encoding/json"
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

	summary := BuildWithOptions(starts, notifications, Options{SLAThresholdMs: 4600})

	if summary.Transactions.Started != 3 {
		t.Fatalf("Started = %d, want 3", summary.Transactions.Started)
	}
	if summary.Transactions.Accepted != 2 {
		t.Fatalf("Accepted = %d, want 2", summary.Transactions.Accepted)
	}
	if summary.Transactions.Confirmation.Confirmed != 2 {
		t.Fatalf("Confirmed = %d, want 2", summary.Transactions.Confirmation.Confirmed)
	}
	if summary.Transactions.ConfirmedBySLA.AfterSLA != 1 {
		t.Fatalf("AfterSLA = %d, want 1", summary.Transactions.ConfirmedBySLA.AfterSLA)
	}
	if summary.Transactions.ConfirmedBySLA.WithinSLA != 1 {
		t.Fatalf("WithinSLA = %d, want 1", summary.Transactions.ConfirmedBySLA.WithinSLA)
	}
	if summary.Transactions.Confirmation.NotConfirmed != 0 {
		t.Fatalf("NotConfirmed = %d, want 0", summary.Transactions.Confirmation.NotConfirmed)
	}
}

func TestSummaryCountsNeverConfirmed(t *testing.T) {
	starts := []events.Start{
		{EndToEndID: "tx-1", PayerISPB: "10000001", CreatedAtNS: 0, HTTPStatus: 200},
	}

	summary := BuildWithOptions(starts, nil, Options{SLAThresholdMs: 4600})

	if summary.Transactions.Confirmation.NotConfirmed != 1 {
		t.Fatalf("NotConfirmed = %d, want 1", summary.Transactions.Confirmation.NotConfirmed)
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

	summary := BuildWithOptions(starts, notifications, Options{SLAThresholdMs: 4600})

	if summary.LatencyMs.P50 != 1000 {
		t.Fatalf("P50 = %f, want 1000", summary.LatencyMs.P50)
	}
}

func TestSummaryMeasuresLatencyFromRequestStart(t *testing.T) {
	starts := []events.Start{
		{
			EndToEndID:         "tx-1",
			PayerISPB:          "10000001",
			CreatedAtNS:        0,
			RequestStartedAtNS: 2_000_000_000,
			HTTPStatus:         200,
		},
	}
	notifications := []events.Notification{
		{EndToEndID: "tx-1", ISPB: "10000001", EventType: events.EventPacs002Received, ReceivedAtNS: 3_000_000_000},
	}

	summary := BuildWithOptions(starts, notifications, Options{SLAThresholdMs: 1500})

	if summary.LatencyMs.P50 != 1000 {
		t.Fatalf("P50 = %f, want 1000", summary.LatencyMs.P50)
	}
	if summary.Transactions.ConfirmedBySLA.WithinSLA != 1 {
		t.Fatalf("WithinSLA = %d, want 1", summary.Transactions.ConfirmedBySLA.WithinSLA)
	}
}

func TestSummaryUsesRequestStartForMeasuredWindow(t *testing.T) {
	starts := []events.Start{
		{EndToEndID: "warmup", PayerISPB: "10000000", CreatedAtNS: 0, RequestStartedAtNS: 0, HTTPStatus: 200},
		{EndToEndID: "queued-before-active", PayerISPB: "10000001", CreatedAtNS: 1_000_000_000, RequestStartedAtNS: 11_000_000_000, HTTPStatus: 200},
		{EndToEndID: "after-active", PayerISPB: "10000002", CreatedAtNS: 2_000_000_000, RequestStartedAtNS: 16_000_000_000, HTTPStatus: 200},
	}

	summary := BuildWithOptions(starts, nil, Options{
		SLAThresholdMs: 4600,
		Warmup:         10 * time.Second,
		Duration:       5 * time.Second,
	})

	if summary.Transactions.Started != 1 {
		t.Fatalf("Started = %d, want 1", summary.Transactions.Started)
	}
}

func TestSummaryReportsConfiguredStartRate(t *testing.T) {
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
}

func TestSummaryIncludesRunConfiguration(t *testing.T) {
	summary := BuildWithOptions(nil, nil, Options{
		SLAThresholdMs: 1000,
		TargetTxRate:   2000,
		Warmup:         30 * time.Second,
		Duration:       180 * time.Second,
	})

	if summary.Run.TargetTPS != 2000 {
		t.Fatalf("TargetTPS = %d, want 2000", summary.Run.TargetTPS)
	}
	if summary.Run.WarmupSeconds != 30 {
		t.Fatalf("WarmupSeconds = %f, want 30", summary.Run.WarmupSeconds)
	}
	if summary.Run.ActiveSeconds != 180 {
		t.Fatalf("ActiveSeconds = %f, want 180", summary.Run.ActiveSeconds)
	}
	if summary.Run.SLAThresholdMs != 1000 {
		t.Fatalf("SLAThresholdMs = %d, want 1000", summary.Run.SLAThresholdMs)
	}
}

func TestSummaryJSONUsesFinalReportShape(t *testing.T) {
	summary := BuildWithOptions([]events.Start{
		{EndToEndID: "tx-1", PayerISPB: "10000001", CreatedAtNS: 0, HTTPStatus: 200},
	}, []events.Notification{
		{EndToEndID: "tx-1", ISPB: "10000001", EventType: events.EventPacs002Received, ReceivedAtNS: 1_000_000},
	}, Options{
		SLAThresholdMs: 1000,
		TargetTxRate:   2000,
		Duration:       time.Second,
	})

	data, err := json.Marshal(summary)
	if err != nil {
		t.Fatal(err)
	}
	var root map[string]any
	if err := json.Unmarshal(data, &root); err != nil {
		t.Fatal(err)
	}

	if _, ok := root["windows"]; ok {
		t.Fatal("summary contains deprecated windows section")
	}
	if _, ok := root["sla"]; ok {
		t.Fatal("summary contains deprecated sla section")
	}

	transactions := root["transactions"].(map[string]any)
	if _, ok := transactions["confirmation"]; !ok {
		t.Fatal("transactions missing confirmation section")
	}
	if _, ok := transactions["confirmed_by_sla"]; !ok {
		t.Fatal("transactions missing confirmed_by_sla section")
	}

	throughput := root["throughput_per_second"].(map[string]any)
	if _, ok := throughput["confirmed_during_active"]; !ok {
		t.Fatal("throughput missing confirmed_during_active")
	}

	diagnostics := root["diagnostics"].(map[string]any)
	resultCollection := diagnostics["result_collection"].(map[string]any)
	if _, ok := resultCollection["confirmed_total"]; !ok {
		t.Fatal("diagnostics missing confirmed_total")
	}
	if _, ok := diagnostics["resources"]; ok {
		t.Fatal("diagnostics contains resource summary that belongs in Prometheus/Grafana")
	}
}

func TestSummaryReportsResultCollectionDiagnosticsOutsideActiveWindow(t *testing.T) {
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

	if summary.ThroughputPerSecond.ConfirmedDuringActive != 1 {
		t.Fatalf("ConfirmedDuringActive = %f, want 1", summary.ThroughputPerSecond.ConfirmedDuringActive)
	}
	if summary.Diagnostics.ResultCollection.ConfirmedAfterActive != 1 {
		t.Fatalf("ConfirmedAfterActive = %d, want 1", summary.Diagnostics.ResultCollection.ConfirmedAfterActive)
	}
	if summary.Diagnostics.ResultCollection.ConfirmedTotal != 3 {
		t.Fatalf("ConfirmedTotal = %d, want 3", summary.Diagnostics.ResultCollection.ConfirmedTotal)
	}
	if summary.Diagnostics.ResultCollection.ConfirmedTotalRate != 1.5 {
		t.Fatalf("ConfirmedTotalRate = %f, want 1.5", summary.Diagnostics.ResultCollection.ConfirmedTotalRate)
	}
}

func TestSummaryExcludesWarmupTransactionsFromMeasuredWindow(t *testing.T) {
	starts := []events.Start{
		{EndToEndID: "warmup-tx", PayerISPB: "10000001", CreatedAtNS: 1_000_000_000, HTTPStatus: 200},
		{EndToEndID: "measured-tx-1", PayerISPB: "10000002", CreatedAtNS: 11_000_000_000, HTTPStatus: 200},
		{EndToEndID: "measured-tx-2", PayerISPB: "10000003", CreatedAtNS: 12_000_000_000, HTTPStatus: 200},
		{EndToEndID: "after-window-tx", PayerISPB: "10000004", CreatedAtNS: 16_000_000_000, HTTPStatus: 200},
	}
	notifications := []events.Notification{
		{EndToEndID: "warmup-tx", ISPB: "10000001", EventType: events.EventPacs002Received, ReceivedAtNS: 1_500_000_000},
		{EndToEndID: "measured-tx-1", ISPB: "10000002", EventType: events.EventPacs002Received, ReceivedAtNS: 11_500_000_000},
		{EndToEndID: "measured-tx-2", ISPB: "10000003", EventType: events.EventPacs002Received, ReceivedAtNS: 17_000_000_000},
		{EndToEndID: "after-window-tx", ISPB: "10000004", EventType: events.EventPacs002Received, ReceivedAtNS: 16_500_000_000},
	}

	summary := BuildWithOptions(starts, notifications, Options{
		SLAThresholdMs: 4600,
		Warmup:         10 * time.Second,
		Duration:       5 * time.Second,
	})

	if summary.Transactions.Started != 2 {
		t.Fatalf("Started = %d, want 2", summary.Transactions.Started)
	}
	if summary.Transactions.Accepted != 2 {
		t.Fatalf("Accepted = %d, want 2", summary.Transactions.Accepted)
	}
	if summary.Transactions.ConfirmedBySLA.WithinSLA != 1 {
		t.Fatalf("WithinSLA = %d, want 1", summary.Transactions.ConfirmedBySLA.WithinSLA)
	}
	if summary.Transactions.ConfirmedBySLA.AfterSLA != 1 {
		t.Fatalf("AfterSLA = %d, want 1", summary.Transactions.ConfirmedBySLA.AfterSLA)
	}
	if summary.ThroughputPerSecond.Started != 0.4 {
		t.Fatalf("Started throughput = %f, want 0.4", summary.ThroughputPerSecond.Started)
	}
}
