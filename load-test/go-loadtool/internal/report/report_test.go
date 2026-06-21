package report

import (
	"encoding/json"
	"os"
	"path/filepath"
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

func TestSummaryReportsActiveWindowContainerResourceAverages(t *testing.T) {
	firstStartedAt := time.Date(2026, 6, 20, 18, 0, 0, 0, time.UTC)
	starts := []events.Start{
		{EndToEndID: "tx-1", PayerISPB: "10000001", CreatedAtNS: firstStartedAt.UnixNano(), HTTPStatus: 200},
	}
	options := Options{
		SLAThresholdMs: 1000,
		Warmup:         10 * time.Second,
		Duration:       20 * time.Second,
		SystemStats: []SystemStatSample{
			{Timestamp: firstStartedAt.Add(5 * time.Second), Source: "container", Name: "spi", CPUPercent: 90, CPULimitPercent: 75, MemUsedMB: 700, MemLimitMB: 768},
			{Timestamp: firstStartedAt.Add(10 * time.Second), Source: "container", Name: "spi", CPUPercent: 40, CPULimitPercent: 75, MemUsedMB: 300, MemLimitMB: 768},
			{Timestamp: firstStartedAt.Add(20 * time.Second), Source: "container", Name: "spi", CPUPercent: 60, CPULimitPercent: 75, MemUsedMB: 450, MemLimitMB: 768},
			{Timestamp: firstStartedAt.Add(29 * time.Second), Source: "container", Name: "spi", CPUPercent: 80, CPULimitPercent: 75, MemUsedMB: 600, MemLimitMB: 768},
			{Timestamp: firstStartedAt.Add(30 * time.Second), Source: "container", Name: "spi", CPUPercent: 10, CPULimitPercent: 75, MemUsedMB: 100, MemLimitMB: 768},
			{Timestamp: firstStartedAt.Add(20 * time.Second), Source: "host", Name: "host", CPUPercent: 99, MemUsedMB: 1000, MemLimitMB: 2000},
		},
	}

	summary := BuildWithOptions(starts, nil, options)

	resources := summary.Diagnostics.Resources
	if resources == nil {
		t.Fatal("Resources is nil")
	}
	spi := resources.Containers["spi"]
	if spi.Samples != 3 {
		t.Fatalf("Samples = %d, want 3", spi.Samples)
	}
	if spi.CPU == nil {
		t.Fatal("CPU is nil")
	}
	if spi.CPU.AvgPercent != 60 {
		t.Fatalf("CPU AvgPercent = %f, want 60", spi.CPU.AvgPercent)
	}
	if spi.CPU.LimitPercent == nil || *spi.CPU.LimitPercent != 75 {
		t.Fatalf("CPU LimitPercent = %v, want 75", spi.CPU.LimitPercent)
	}
	if spi.Memory == nil {
		t.Fatal("Memory is nil")
	}
	if spi.Memory.AvgMB != 450 {
		t.Fatalf("Memory AvgMB = %f, want 450", spi.Memory.AvgMB)
	}
	if spi.Memory.LimitMB != 768 {
		t.Fatalf("Memory LimitMB = %f, want 768", spi.Memory.LimitMB)
	}
	wantMemoryPercent := 450.0 / 768.0 * 100
	if spi.Memory.AvgOfLimitPercent != wantMemoryPercent {
		t.Fatalf("Memory AvgOfLimitPercent = %f, want %f", spi.Memory.AvgOfLimitPercent, wantMemoryPercent)
	}
}

func TestReadSystemStatsParsesContainerCpuLimit(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "system-stats.csv")
	csv := "timestamp,source,name,cpu_percent,cpu_limit_percent,mem_used_mb,mem_limit_mb,mem_available_mb,load_1m,block_io,net_io\n" +
		"2026-06-20T18:00:10Z,container,spi,55.5,75.0,456.7,768.0,,,52MB / 10MB,1GB / 2GB\n" +
		"2026-06-20T18:00:10Z,host,host,20.0,,1000,16000,15000,0.50,,\n"
	if err := os.WriteFile(path, []byte(csv), 0o600); err != nil {
		t.Fatal(err)
	}

	stats, err := ReadSystemStats(path)
	if err != nil {
		t.Fatal(err)
	}

	if len(stats) != 2 {
		t.Fatalf("len(stats) = %d, want 2", len(stats))
	}
	if stats[0].Name != "spi" {
		t.Fatalf("Name = %s, want spi", stats[0].Name)
	}
	if stats[0].CPUPercent != 55.5 {
		t.Fatalf("CPUPercent = %f, want 55.5", stats[0].CPUPercent)
	}
	if stats[0].CPULimitPercent != 75 {
		t.Fatalf("CPULimitPercent = %f, want 75", stats[0].CPULimitPercent)
	}
	if stats[1].Source != "host" {
		t.Fatalf("Source = %s, want host", stats[1].Source)
	}
}
