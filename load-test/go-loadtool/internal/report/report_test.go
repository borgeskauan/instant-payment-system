package report

import (
	"encoding/json"
	"strings"
	"testing"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/config"
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

	summary := mustBuildSummary(t, starts, notifications, Options{SLAThresholdMs: 4600})

	if summary.Transactions.Started != 3 {
		t.Fatalf("Started = %d, want 3", summary.Transactions.Started)
	}
	if summary.Transactions.Accepted != 2 {
		t.Fatalf("Accepted = %d, want 2", summary.Transactions.Accepted)
	}
	if summary.Transactions.PayerNotification.Notified != 2 {
		t.Fatalf("Notified = %d, want 2", summary.Transactions.PayerNotification.Notified)
	}
	if summary.Transactions.PayerNotifiedBySLA.AfterSLA != 1 {
		t.Fatalf("AfterSLA = %d, want 1", summary.Transactions.PayerNotifiedBySLA.AfterSLA)
	}
	if summary.Transactions.PayerNotifiedBySLA.WithinSLA != 1 {
		t.Fatalf("WithinSLA = %d, want 1", summary.Transactions.PayerNotifiedBySLA.WithinSLA)
	}
	if summary.Transactions.PayerNotification.NotNotified != 0 {
		t.Fatalf("NotNotified = %d, want 0", summary.Transactions.PayerNotification.NotNotified)
	}
}

func TestSummaryCountsMissingPayerNotification(t *testing.T) {
	starts := []events.Start{
		{EndToEndID: "tx-1", PayerISPB: "10000001", CreatedAtNS: 0, HTTPStatus: 200},
	}

	summary := mustBuildSummary(t, starts, nil, Options{SLAThresholdMs: 4600})

	if summary.Transactions.PayerNotification.NotNotified != 1 {
		t.Fatalf("NotNotified = %d, want 1", summary.Transactions.PayerNotification.NotNotified)
	}
}

func TestSummaryAllowsRepeatedExpectedPayerNotificationsAndUsesEarliest(t *testing.T) {
	starts := []events.Start{
		{EndToEndID: "tx-1", PayerISPB: "10000001", CreatedAtNS: 0, HTTPStatus: 200},
	}
	notifications := []events.Notification{
		{EndToEndID: "tx-1", ISPB: "10000001", EventType: events.EventPacs002Received, StatusCode: "ACSC", ReasonCodes: []string{}, ReceivedAtNS: 3_000_000_000},
		{EndToEndID: "tx-1", ISPB: "20000001", EventType: events.EventPacs002Received, ReceivedAtNS: 500_000_000},
		{EndToEndID: "tx-1", ISPB: "10000001", EventType: events.EventPacs002Received, StatusCode: "ACSC", ReasonCodes: []string{}, ReceivedAtNS: 1_000_000_000},
	}

	summary := mustBuildSummary(t, starts, notifications, Options{SLAThresholdMs: 4600})

	if summary.PayerNotificationLatencyMs.P50 != 1000 {
		t.Fatalf("P50 = %f, want 1000", summary.PayerNotificationLatencyMs.P50)
	}
	got := summary.Scenarios[0].Transactions.PayerNotification
	if got.Observed != 1 || got.Matched != 1 || got.Violations != 0 || summary.Transactions.PayerNotification.Notified != 1 {
		t.Fatalf("payer notification summary = %#v", summary.Scenarios[0].Transactions.PayerNotification)
	}
}

func TestSummaryRejectsContradictoryPayerNotificationAlongsideExpectedOutcome(t *testing.T) {
	starts := []events.Start{{EndToEndID: "tx-1", PayerISPB: "10000001", RequestStartedAtNS: 1_000_000_000, HTTPStatus: 200}}
	notifications := []events.Notification{
		{EndToEndID: "tx-1", ISPB: "10000001", EventType: events.EventPacs002Received, StatusCode: "ACSC", ReasonCodes: []string{}, ReceivedAtNS: 2_000_000_000},
		{EndToEndID: "tx-1", ISPB: "10000001", EventType: events.EventPacs002Received, StatusCode: "RJCT", ReasonCodes: []string{"AM04"}, ReceivedAtNS: 3_000_000_000},
	}

	summary := mustBuildSummary(t, starts, notifications, Options{SLAThresholdMs: 1_500})
	got := summary.Scenarios[0].Transactions.PayerNotification
	if got.Observed != 1 || got.Matched != 1 || got.StatusMismatch != 1 || got.ReasonCodesMismatch != 1 || got.Violations != 1 {
		t.Fatalf("payer notification summary = %#v", got)
	}
	if summary.PayerNotificationLatencyMs.P50 != 1000 || summary.Diagnostics.ResultCollection.PayerNotifiedTotal != 1 || summary.Transactions.PayerNotifiedBySLA.WithinSLA != 1 {
		t.Fatalf("matching outcome was not counted once in performance metrics: summary=%#v", summary)
	}
}

func TestSummaryComparesReasonCodesWithoutDependingOnOrder(t *testing.T) {
	scenario := reportTestInsufficientFundsScenario()
	scenario.Expectations.PayerNotification.ReasonCodes = []string{"AM04", "AB03"}
	summary := mustBuildSummary(t, []events.Start{{
		EndToEndID: "tx-1", PayerISPB: "10000041", HTTPStatus: 200, ScenarioName: scenario.Name,
	}}, []events.Notification{{
		EndToEndID: "tx-1", ISPB: "10000041", EventType: events.EventPacs002Received,
		StatusCode: "RJCT", ReasonCodes: []string{"AB03", "AM04"},
	}}, Options{Scenarios: []config.Scenario{scenario}})

	got := summary.Scenarios[0].Transactions.PayerNotification
	if got.Matched != 1 || got.ReasonCodesMismatch != 0 || got.Violations != 0 {
		t.Fatalf("payer notification summary = %#v", got)
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

	summary := mustBuildSummary(t, starts, notifications, Options{SLAThresholdMs: 1500})

	if summary.PayerNotificationLatencyMs.P50 != 1000 {
		t.Fatalf("P50 = %f, want 1000", summary.PayerNotificationLatencyMs.P50)
	}
	if summary.Transactions.PayerNotifiedBySLA.WithinSLA != 1 {
		t.Fatalf("WithinSLA = %d, want 1", summary.Transactions.PayerNotifiedBySLA.WithinSLA)
	}
}

func TestSummaryUsesRequestStartForMeasuredWindow(t *testing.T) {
	starts := []events.Start{
		{EndToEndID: "warmup", PayerISPB: "10000000", CreatedAtNS: 0, RequestStartedAtNS: 0, HTTPStatus: 200},
		{EndToEndID: "queued-before-active", PayerISPB: "10000001", CreatedAtNS: 1_000_000_000, RequestStartedAtNS: 11_000_000_000, HTTPStatus: 200},
		{EndToEndID: "after-active", PayerISPB: "10000002", CreatedAtNS: 2_000_000_000, RequestStartedAtNS: 16_000_000_000, HTTPStatus: 200},
	}

	summary := mustBuildSummary(t, starts, nil, Options{
		SLAThresholdMs: 4600,
		Warmup:         10 * time.Second,
		Duration:       5 * time.Second,
	})

	if summary.Transactions.Started != 3 {
		t.Fatalf("full-run Started = %d, want 3", summary.Transactions.Started)
	}
	if summary.ThroughputPerSecond.Started != 0.2 {
		t.Fatalf("active-window throughput = %f, want 0.2", summary.ThroughputPerSecond.Started)
	}
}

func TestSummaryReportsCompactReplayCountsAndIngressRates(t *testing.T) {
	starts := []events.Start{
		{EndToEndID: "tx-1", PayerISPB: "10000001", ScenarioName: "happy-path", RequestStartedAtNS: 1_000_000_000, HTTPStatus: 200, Pacs008ReplaySelected: true},
		{EndToEndID: "tx-2", PayerISPB: "10000002", ScenarioName: "happy-path", RequestStartedAtNS: 6_000_000_000, HTTPStatus: 200, Pacs008ReplaySelected: true},
	}
	replays := []events.Replay{
		{EndToEndID: "tx-1", PayerISPB: "10000001", ScenarioName: "happy-path", MessageType: events.MessagePacs008, RequestStartedAtNS: 11_000_000_000, HTTPStatus: 200},
		{EndToEndID: "tx-2", PayerISPB: "10000002", ScenarioName: "happy-path", MessageType: events.MessagePacs008, RequestStartedAtNS: 16_000_000_000, HTTPStatus: 202},
	}
	options := Options{
		Duration: 20 * time.Second,
		Replay: config.Replay{Pacs008: &config.Pacs008Replay{
			Share: 0.10,
			Delay: 10 * time.Second,
		}},
	}
	summary := mustBuildSummaryWithReplays(t, starts, nil, replays, options)

	if summary.Replays.Pacs008.Attempted != 2 || summary.Replays.Pacs008.Accepted != 2 || summary.Replays.Pacs008.Violations != 0 {
		t.Fatalf("replay summary = %#v", summary.Replays.Pacs008)
	}
	if summary.ThroughputPerSecond.OriginalPaymentsStarted != 0.1 || summary.ThroughputPerSecond.Pacs008ReplaysStarted != 0.1 || summary.ThroughputPerSecond.TotalIngressStarted != 0.2 {
		t.Fatalf("throughput = %#v", summary.ThroughputPerSecond)
	}
	encoded, err := json.Marshal(summary.Replays.Pacs008)
	if err != nil {
		t.Fatal(err)
	}
	var fields map[string]any
	if err := json.Unmarshal(encoded, &fields); err != nil {
		t.Fatal(err)
	}
	if len(fields) != 3 || fields["attempted"] != float64(2) || fields["accepted"] != float64(2) || fields["violations"] != float64(0) {
		t.Fatalf("public replay fields = %#v", fields)
	}
}

func TestSummaryAggregatesReplayGeneratorDefectsWithoutPublicTaxonomy(t *testing.T) {
	validStart := events.Start{
		EndToEndID:            "tx-1",
		PayerISPB:             "10000001",
		ScenarioName:          "happy-path",
		RequestStartedAtNS:    1_000_000_000,
		HTTPStatus:            200,
		Pacs008ReplaySelected: true,
	}
	validReplay := events.Replay{
		EndToEndID:         "tx-1",
		PayerISPB:          "10000001",
		ScenarioName:       "happy-path",
		MessageType:        events.MessagePacs008,
		RequestStartedAtNS: 11_000_000_000,
		HTTPStatus:         200,
	}
	options := Options{Replay: config.Replay{Pacs008: &config.Pacs008Replay{Share: 0.10, Delay: 10 * time.Second}}}
	tests := []struct {
		name    string
		starts  []events.Start
		replays []events.Replay
	}{
		{name: "missing", starts: []events.Start{validStart}},
		{name: "not selected", starts: []events.Start{func() events.Start { value := validStart; value.Pacs008ReplaySelected = false; return value }()}, replays: []events.Replay{validReplay}},
		{name: "excess", starts: []events.Start{validStart}, replays: []events.Replay{validReplay, validReplay}},
		{name: "unknown", starts: []events.Start{validStart}, replays: []events.Replay{func() events.Replay { value := validReplay; value.EndToEndID = "unknown"; return value }()}},
		{name: "metadata", starts: []events.Start{validStart}, replays: []events.Replay{func() events.Replay { value := validReplay; value.PayerISPB = "10000002"; return value }()}},
		{name: "http", starts: []events.Start{validStart}, replays: []events.Replay{func() events.Replay { value := validReplay; value.HTTPStatus = 500; return value }()}},
		{name: "early", starts: []events.Start{validStart}, replays: []events.Replay{func() events.Replay { value := validReplay; value.RequestStartedAtNS--; return value }()}},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			summary := mustBuildSummaryWithReplays(t, test.starts, nil, test.replays, options)
			if summary.Replays.Pacs008.Violations == 0 {
				t.Fatalf("replay summary = %#v, want aggregate violation", summary.Replays.Pacs008)
			}
		})
	}
}

func TestSummaryReportsConfiguredStartRate(t *testing.T) {
	starts := []events.Start{
		{EndToEndID: "tx-1", PayerISPB: "10000001", CreatedAtNS: 0, HTTPStatus: 200},
		{EndToEndID: "tx-2", PayerISPB: "10000002", CreatedAtNS: 500_000_000, HTTPStatus: 200},
		{EndToEndID: "tx-3", PayerISPB: "10000003", CreatedAtNS: 1_000_000_000, HTTPStatus: 200},
	}

	summary := mustBuildSummary(t, starts, nil, Options{
		SLAThresholdMs: 4600,
		TargetTxRate:   2,
		Duration:       2 * time.Second,
	})

	if summary.ThroughputPerSecond.Started != 1.5 {
		t.Fatalf("Started throughput = %f, want 1.5", summary.ThroughputPerSecond.Started)
	}
}

func TestSummaryIncludesRunConfiguration(t *testing.T) {
	summary := mustBuildSummary(t, nil, nil, Options{
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
	summary := mustBuildSummary(t, []events.Start{
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
	if _, ok := transactions["payer_notification"]; !ok {
		t.Fatal("transactions missing payer_notification section")
	}
	if _, ok := transactions["payer_notified_by_sla"]; !ok {
		t.Fatal("transactions missing payer_notified_by_sla section")
	}
	if _, ok := root["payer_notification_latency_ms"]; !ok {
		t.Fatal("summary missing payer_notification_latency_ms section")
	}
	scenarioTransactions := root["scenarios"].([]any)[0].(map[string]any)["transactions"].(map[string]any)
	payerNotification := scenarioTransactions["payer_notification"].(map[string]any)
	if payerNotification["delivery_semantics"] != "at-least-once" || payerNotification["expected_status"] != "ACSC" {
		t.Fatalf("payer notification expectation missing from report: %#v", payerNotification)
	}
	if _, ok := payerNotification["expected_count"]; ok {
		t.Fatalf("report retained exact-count expectation: %#v", payerNotification)
	}
	if _, ok := payerNotification["excess"]; ok {
		t.Fatalf("report treats repeated delivery as excess: %#v", payerNotification)
	}

	throughput := root["throughput_per_second"].(map[string]any)
	if _, ok := throughput["payer_notified_during_active"]; !ok {
		t.Fatal("throughput missing payer_notified_during_active")
	}

	diagnostics := root["diagnostics"].(map[string]any)
	resultCollection := diagnostics["result_collection"].(map[string]any)
	if _, ok := resultCollection["payer_notified_total"]; !ok {
		t.Fatal("diagnostics missing payer_notified_total")
	}
	if strings.Contains(string(data), "confirm") {
		t.Fatalf("summary retains obsolete confirmation terminology: %s", data)
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

	summary := mustBuildSummary(t, starts, notifications, Options{
		SLAThresholdMs: 4600,
		Duration:       2 * time.Second,
	})

	if summary.ThroughputPerSecond.PayerNotifiedDuringActive != 1 {
		t.Fatalf("PayerNotifiedDuringActive = %f, want 1", summary.ThroughputPerSecond.PayerNotifiedDuringActive)
	}
	if summary.Diagnostics.ResultCollection.PayerNotifiedAfterActive != 1 {
		t.Fatalf("PayerNotifiedAfterActive = %d, want 1", summary.Diagnostics.ResultCollection.PayerNotifiedAfterActive)
	}
	if summary.Diagnostics.ResultCollection.PayerNotifiedTotal != 3 {
		t.Fatalf("PayerNotifiedTotal = %d, want 3", summary.Diagnostics.ResultCollection.PayerNotifiedTotal)
	}
	if summary.Diagnostics.ResultCollection.PayerNotifiedTotalRate != 1.5 {
		t.Fatalf("PayerNotifiedTotalRate = %f, want 1.5", summary.Diagnostics.ResultCollection.PayerNotifiedTotalRate)
	}
}

func TestSummaryValidatesFullRunButMeasuresOnlyActiveWindow(t *testing.T) {
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

	summary := mustBuildSummary(t, starts, notifications, Options{
		SLAThresholdMs: 4600,
		Warmup:         10 * time.Second,
		Duration:       5 * time.Second,
	})

	if summary.Transactions.Started != 4 {
		t.Fatalf("full-run Started = %d, want 4", summary.Transactions.Started)
	}
	if summary.Transactions.Accepted != 4 {
		t.Fatalf("full-run Accepted = %d, want 4", summary.Transactions.Accepted)
	}
	if summary.Transactions.PayerNotifiedBySLA.WithinSLA != 1 {
		t.Fatalf("WithinSLA = %d, want 1", summary.Transactions.PayerNotifiedBySLA.WithinSLA)
	}
	if summary.Transactions.PayerNotifiedBySLA.AfterSLA != 1 {
		t.Fatalf("AfterSLA = %d, want 1", summary.Transactions.PayerNotifiedBySLA.AfterSLA)
	}
	if summary.ThroughputPerSecond.Started != 0.4 {
		t.Fatalf("Started throughput = %f, want 0.4", summary.ThroughputPerSecond.Started)
	}
}

func TestSummaryUsesConfiguredScenarioName(t *testing.T) {
	scenario := reportTestHappyPathScenario()
	summary := mustBuildSummary(t, []events.Start{{
		EndToEndID:   "tx-1",
		PayerISPB:    "10000001",
		HTTPStatus:   200,
		ScenarioName: "happy-path",
	}}, []events.Notification{{
		EndToEndID:   "tx-1",
		ISPB:         "10000001",
		EventType:    events.EventPacs002Received,
		ReceivedAtNS: 1_000_000,
	}}, Options{
		SLAThresholdMs: 1000,
		Scenarios:      []config.Scenario{scenario},
	})

	if summary.Transactions.Accepted != 1 || summary.Transactions.PayerNotification.Notified != 1 {
		t.Fatalf("Transactions = %#v", summary.Transactions)
	}
}

func TestSummaryRejectsUnknownScenarioName(t *testing.T) {
	_, err := BuildWithOptions([]events.Start{{
		EndToEndID:   "tx-1",
		ScenarioName: "not-configured",
	}}, nil, Options{Scenarios: []config.Scenario{reportTestHappyPathScenario()}})

	if err == nil {
		t.Fatal("BuildWithOptions accepted unknown scenario name")
	}
}

func TestSummaryReportsMixedScenarioNotificationCounts(t *testing.T) {
	scenarios := []config.Scenario{reportTestHappyPathScenario(), reportTestInsufficientFundsScenario()}
	scenarios[0].Share = 0.8
	starts := []events.Start{
		{EndToEndID: "happy-notified", PayerISPB: "10000001", HTTPStatus: 200, ScenarioName: "happy-path"},
		{EndToEndID: "happy-missing", PayerISPB: "10000002", HTTPStatus: 200, ScenarioName: "happy-path"},
		{EndToEndID: "insufficient-notified", PayerISPB: "10000041", HTTPStatus: 200, ScenarioName: "insufficient-funds"},
		{EndToEndID: "insufficient-missing", PayerISPB: "10000042", HTTPStatus: 200, ScenarioName: "insufficient-funds"},
		{EndToEndID: "insufficient-http", PayerISPB: "10000043", HTTPStatus: 500, ScenarioName: "insufficient-funds"},
	}
	notifications := []events.Notification{
		{EndToEndID: "happy-notified", ISPB: "10000001", EventType: events.EventPacs002Received, ReceivedAtNS: 1_000_000},
		{EndToEndID: "insufficient-notified", ISPB: "10000041", EventType: events.EventPacs002Received, ReceivedAtNS: 2_000_000},
	}
	summary := mustBuildSummary(t, starts, notifications, Options{SLAThresholdMs: 1000, Scenarios: scenarios})

	if summary.Transactions.Started != 5 || summary.Transactions.Accepted != 4 {
		t.Fatalf("aggregate transaction counts = %#v", summary.Transactions)
	}
	if summary.Transactions.PayerNotification.Notified != 2 || summary.Transactions.PayerNotification.NotNotified != 2 {
		t.Fatalf("aggregate payer notifications = %#v", summary.Transactions.PayerNotification)
	}
	if len(summary.Scenarios) != 2 || summary.Scenarios[0].Name != "happy-path" || summary.Scenarios[1].Name != "insufficient-funds" {
		t.Fatalf("ordered scenarios = %#v", summary.Scenarios)
	}
	happy := summary.Scenarios[0].Transactions
	if happy.PayerNotification.Observed != 1 || happy.PayerNotification.Missing != 1 || happy.PayerNotification.Violations != 1 || happy.Violations != 1 {
		t.Fatalf("happy-path summary = %#v", happy)
	}
	insufficient := summary.Scenarios[1].Transactions
	if insufficient.Started != 3 || insufficient.Accepted != 2 || insufficient.PayerNotification.Observed != 1 || insufficient.PayerNotification.Missing != 1 {
		t.Fatalf("insufficient-funds counts = %#v", insufficient)
	}
	if insufficient.HTTPStatus.Violations != 1 || insufficient.PayerNotification.Violations != 1 || insufficient.Violations != 2 {
		t.Fatalf("insufficient-funds violations = %#v", insufficient)
	}
}

func TestSummaryRejectsUntaggedStartsForMixedProfile(t *testing.T) {
	_, err := BuildWithOptions([]events.Start{{EndToEndID: "untagged", HTTPStatus: 200}}, nil, Options{
		Scenarios: []config.Scenario{reportTestHappyPathScenario(), reportTestInsufficientFundsScenario()},
	})
	if err == nil || err.Error() == "" {
		t.Fatal("mixed report accepted an untagged start")
	}
}

func TestSummaryRejectsUntaggedStartForSingleScenario(t *testing.T) {
	_, err := BuildWithOptions([]events.Start{{EndToEndID: "untagged", HTTPStatus: 200}}, nil, Options{
		Scenarios: []config.Scenario{reportTestHappyPathScenario()},
	})
	if err == nil {
		t.Fatal("single-scenario report accepted an untagged start")
	}
}

func mustBuildSummary(t *testing.T, starts []events.Start, notifications []events.Notification, options Options) Summary {
	return mustBuildSummaryWithReplays(t, starts, notifications, nil, options)
}

func mustBuildSummaryWithReplays(t *testing.T, starts []events.Start, notifications []events.Notification, replays []events.Replay, options Options) Summary {
	t.Helper()
	if len(options.Scenarios) == 0 {
		options.Scenarios = []config.Scenario{reportTestHappyPathScenario()}
	}
	if len(options.Scenarios) == 1 {
		for index := range starts {
			if starts[index].ScenarioName == "" {
				starts[index].ScenarioName = options.Scenarios[0].Name
			}
		}
	}
	startsByID := make(map[string]events.Start, len(starts))
	for _, start := range starts {
		startsByID[start.EndToEndID] = start
	}
	for index := range notifications {
		if notifications[index].EventType != events.EventPacs002Received || notifications[index].StatusCode != "" || notifications[index].ReasonCodes != nil {
			continue
		}
		start, exists := startsByID[notifications[index].EndToEndID]
		if !exists {
			continue
		}
		for _, scenario := range options.Scenarios {
			if scenario.Name == start.ScenarioName {
				notifications[index].StatusCode = scenario.Expectations.PayerNotification.Status
				notifications[index].ReasonCodes = append([]string(nil), scenario.Expectations.PayerNotification.ReasonCodes...)
				break
			}
		}
	}
	summary, err := BuildWithReplayOptions(starts, notifications, replays, options)
	if err != nil {
		t.Fatal(err)
	}
	return summary
}

func reportTestHappyPathScenario() config.Scenario {
	return config.Scenario{
		Name:  "happy-path",
		Share: 1,
		Expectations: config.ScenarioExpectations{
			HTTPStatus:        config.ExpectedHTTP2xx,
			PayerNotification: config.PayerNotificationExpectation{DeliverySemantics: config.DeliveryAtLeastOnce, Status: "ACSC", ReasonCodes: []string{}},
		},
	}
}

func reportTestInsufficientFundsScenario() config.Scenario {
	return config.Scenario{
		Name:  "insufficient-funds",
		Share: 0.2,
		Expectations: config.ScenarioExpectations{
			HTTPStatus:        config.ExpectedHTTP2xx,
			PayerNotification: config.PayerNotificationExpectation{DeliverySemantics: config.DeliveryAtLeastOnce, Status: "RJCT", ReasonCodes: []string{"AM04"}},
		},
	}
}
