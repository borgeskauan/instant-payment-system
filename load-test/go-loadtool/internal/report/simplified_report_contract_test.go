package report

import (
	"encoding/json"
	"testing"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/config"
	"instant-payment-system/load-test/go-loadtool/internal/events"
	"instant-payment-system/load-test/go-loadtool/internal/runwindow"
)

func TestSummaryUsesScenarioCenteredContract(t *testing.T) {
	scenarios := []config.Scenario{reportTestHappyPathScenario(), reportTestInsufficientFundsScenario()}
	scenarios[0].Share = 0.8
	starts := []events.Start{
		{EndToEndID: "happy", PayerISPB: "10000001", ScenarioName: "happy-path", RequestStartedAtNS: 100_000_000, HTTPStatus: 200},
		{EndToEndID: "insufficient", PayerISPB: "10000041", ScenarioName: "insufficient-funds", RequestStartedAtNS: 200_000_000, HTTPStatus: 202},
	}
	statusStarts := []events.StatusStart{
		{EndToEndID: "happy", ScenarioName: "happy-path", RequestStartedAtNS: 300_000_000, HTTPStatus: 200},
		{EndToEndID: "insufficient", ScenarioName: "insufficient-funds", RequestStartedAtNS: 400_000_000, HTTPStatus: 202},
	}
	notifications := []events.Notification{
		{EndToEndID: "happy", ISPB: "10000001", EventType: events.EventPacs002Received, StatusCode: "ACSC", ReasonCodes: []string{}, ReceivedAtNS: 500_000_000},
		{EndToEndID: "insufficient", ISPB: "10000041", EventType: events.EventPacs002Received, StatusCode: "RJCT", ReasonCodes: []string{"AM04"}, ReceivedAtNS: 600_000_000},
	}
	options := Options{
		OfferedTxRate:         2,
		RequiredMinimumTxRate: 2,
		Duration:              time.Second,
		SLAThresholdMs:        1_000,
		Scenarios:             scenarios,
		Window: runwindow.Window{
			GenerationStartedAt: time.Unix(0, 0),
			ActiveStartedAt:     time.Unix(0, 0),
			GenerationEndedAt:   time.Unix(1, 0),
			ReplayDeadlineAt:    time.Unix(2, 0),
		},
	}

	summary, err := Build(starts, notifications, statusStarts, nil, options)
	if err != nil {
		t.Fatal(err)
	}
	if !summary.Valid {
		t.Fatalf("Valid = false, summary=%#v", summary)
	}
	if summary.Generation.OfferedTPS != 2 || summary.Generation.RequiredMinimumTPS != 2 || summary.Generation.RollingWindowSeconds != 1 || summary.Generation.Started != 2 || summary.Generation.AverageTPS != 2 || summary.Generation.MinimumObservedTPS != 2 || summary.Generation.MaximumObservedTPS != 2 || !summary.Generation.SustainedMinimumMet || summary.Generation.OutsideWindow != 0 {
		t.Fatalf("generation = %#v", summary.Generation)
	}
	if len(summary.Scenarios) != 2 {
		t.Fatalf("scenarios = %#v", summary.Scenarios)
	}
	happy := summary.Scenarios[0]
	if happy.Name != "happy-path" || happy.Share != 0.8 || happy.Traffic.Payments.Started != 1 || happy.Traffic.Payments.Accepted != 1 || happy.Traffic.Pacs002.Started != 1 || happy.Traffic.Pacs002.Accepted != 1 {
		t.Fatalf("happy scenario = %#v", happy)
	}
	if happy.Outcome.Expected.Status != "ACSC" || len(happy.Outcome.Expected.ReasonCodes) != 0 || happy.Outcome.Matched != 1 || happy.Outcome.Missing != 0 || happy.Outcome.Contradictory != 0 || happy.Violations != 0 {
		t.Fatalf("happy outcome = %#v", happy.Outcome)
	}
	insufficient := summary.Scenarios[1]
	if insufficient.Traffic.Pacs002.Started != 1 || insufficient.Outcome.Expected.Status != "RJCT" || len(insufficient.Outcome.Expected.ReasonCodes) != 1 || insufficient.Outcome.Expected.ReasonCodes[0] != "AM04" || insufficient.Outcome.Matched != 1 || insufficient.Violations != 0 {
		t.Fatalf("insufficient scenario = %#v", insufficient)
	}
	if summary.Performance.ThresholdMs != 1_000 || summary.Performance.ActiveTPS.Payments != 2 || summary.Performance.ActiveTPS.Pacs002 != 2 || summary.Performance.ActiveTPS.PayerNotifications != 2 {
		t.Fatalf("performance = %#v", summary.Performance)
	}
}

func TestSummaryJSONOmitsRemovedAggregateBlocks(t *testing.T) {
	summary := Summary{}
	encoded, err := json.Marshal(summary)
	if err != nil {
		t.Fatal(err)
	}
	var document map[string]json.RawMessage
	if err := json.Unmarshal(encoded, &document); err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"valid", "generation", "scenarios", "replays", "notification_pull", "performance"} {
		if _, exists := document[key]; !exists {
			t.Fatalf("new report key %q is absent from %s", key, encoded)
		}
	}
	for _, key := range []string{"run", "transactions", "status_messages", "load_generation", "throughput_per_second", "payer_notification_latency_ms", "diagnostics"} {
		if _, exists := document[key]; exists {
			t.Fatalf("removed report key %q is present in %s", key, encoded)
		}
	}

	var generation map[string]json.RawMessage
	if err := json.Unmarshal(document["generation"], &generation); err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"offered_tps", "required_minimum_tps", "rolling_window_seconds", "started", "average_tps", "minimum_observed_tps", "maximum_observed_tps", "sustained_minimum_met", "outside_window"} {
		if _, exists := generation[key]; !exists {
			t.Fatalf("generation key %q is absent from %s", key, document["generation"])
		}
	}
	for _, key := range []string{"target_tps", "expected", "actual_tps", "intervals_below_target", "violations"} {
		if _, exists := generation[key]; exists {
			t.Fatalf("removed generation key %q is present in %s", key, document["generation"])
		}
	}

	var performance map[string]json.RawMessage
	if err := json.Unmarshal(document["performance"], &performance); err != nil {
		t.Fatal(err)
	}
	if _, exists := performance["within_sla"]; !exists {
		t.Fatalf("performance.within_sla is absent from %s", document["performance"])
	}
}

func TestSummaryAttributesPacs002ViolationsToItsScenario(t *testing.T) {
	scenarios := []config.Scenario{reportTestHappyPathScenario(), reportTestInsufficientFundsScenario()}
	statuses := []events.StatusStart{
		{EndToEndID: "happy", ScenarioName: "happy-path", RequestStartedAtNS: 100_000_000, HTTPStatus: 500},
		{EndToEndID: "insufficient", ScenarioName: "insufficient-funds", RequestStartedAtNS: 200_000_000, HTTPStatus: 202},
	}
	options := Options{
		Scenarios: scenarios,
		Window: runwindow.Window{
			GenerationStartedAt: time.Unix(0, 0),
			ActiveStartedAt:     time.Unix(0, 0),
			GenerationEndedAt:   time.Unix(1, 0),
			ReplayDeadlineAt:    time.Unix(2, 0),
		},
	}

	summary, err := Build(nil, nil, statuses, nil, options)
	if err != nil {
		t.Fatal(err)
	}
	if summary.Scenarios[0].Traffic.Pacs002.Started != 1 || summary.Scenarios[0].Traffic.Pacs002.Accepted != 0 || summary.Scenarios[0].Violations != 1 {
		t.Fatalf("happy-path = %#v", summary.Scenarios[0])
	}
	if summary.Scenarios[1].Traffic.Pacs002.Started != 1 || summary.Scenarios[1].Traffic.Pacs002.Accepted != 1 || summary.Scenarios[1].Violations != 0 {
		t.Fatalf("insufficient-funds = %#v", summary.Scenarios[1])
	}
	if summary.Valid {
		t.Fatal("Valid = true with a scenario PACS.002 violation")
	}
}

func TestSummaryRoundsPublishedMetricsToThreeDecimalPlaces(t *testing.T) {
	starts := []events.Start{{
		EndToEndID: "tx-1", PayerISPB: "10000001", ScenarioName: "happy-path",
		RequestStartedAtNS: 1, HTTPStatus: 200,
	}}
	notifications := []events.Notification{{
		EndToEndID: "tx-1", ISPB: "10000001", EventType: events.EventPacs002Received,
		StatusCode: "ACSC", ReasonCodes: []string{}, ReceivedAtNS: 1_234_568,
	}}
	options := Options{
		Duration:  3 * time.Second,
		Scenarios: []config.Scenario{reportTestHappyPathScenario()},
		Window: runwindow.Window{
			GenerationStartedAt: time.Unix(0, 0),
			ActiveStartedAt:     time.Unix(0, 0),
			GenerationEndedAt:   time.Unix(3, 0),
			ReplayDeadlineAt:    time.Unix(4, 0),
		},
	}

	summary, err := Build(starts, notifications, nil, nil, options)
	if err != nil {
		t.Fatal(err)
	}
	if summary.Generation.AverageTPS != 0.333 || summary.Performance.LatencyMs.P50 != 1.235 || summary.Scenarios[0].Performance.LatencyMs.P50 != 1.235 {
		t.Fatalf("rounded metrics = generation:%#v performance:%#v scenario:%#v", summary.Generation, summary.Performance, summary.Scenarios[0].Performance)
	}
}
