package report

import (
	"testing"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/config"
	"instant-payment-system/load-test/go-loadtool/internal/events"
	"instant-payment-system/load-test/go-loadtool/internal/runwindow"
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

	payments := summary.Scenarios[0].Traffic.Payments
	if payments.Started != 3 {
		t.Fatalf("Started = %d, want 3", payments.Started)
	}
	if payments.Accepted != 2 {
		t.Fatalf("Accepted = %d, want 2", payments.Accepted)
	}
	if summary.Scenarios[0].Outcome.Matched != 2 {
		t.Fatalf("Matched = %d, want 2", summary.Scenarios[0].Outcome.Matched)
	}
	if summary.Scenarios[0].Performance.AfterThreshold != 1 {
		t.Fatalf("AfterThreshold = %d, want 1", summary.Scenarios[0].Performance.AfterThreshold)
	}
	if summary.Scenarios[0].Performance.WithinThreshold != 1 {
		t.Fatalf("WithinThreshold = %d, want 1", summary.Scenarios[0].Performance.WithinThreshold)
	}
	if summary.Scenarios[0].Outcome.Missing != 0 {
		t.Fatalf("Missing = %d, want 0", summary.Scenarios[0].Outcome.Missing)
	}
}

func TestSummaryCountsMissingPayerNotification(t *testing.T) {
	starts := []events.Start{
		{EndToEndID: "tx-1", PayerISPB: "10000001", CreatedAtNS: 0, HTTPStatus: 200},
	}

	summary := mustBuildSummary(t, starts, nil, Options{SLAThresholdMs: 4600})

	if summary.Scenarios[0].Outcome.Missing != 1 || summary.Valid {
		t.Fatalf("outcome = %#v, valid = %t", summary.Scenarios[0].Outcome, summary.Valid)
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

	if summary.Performance.LatencyMs.P50 != 1000 {
		t.Fatalf("P50 = %f, want 1000", summary.Performance.LatencyMs.P50)
	}
	got := summary.Scenarios[0].Outcome
	if got.Matched != 1 || got.Missing != 0 || got.Contradictory != 0 || summary.Scenarios[0].Violations != 0 {
		t.Fatalf("payer notification summary = %#v", got)
	}
}

func TestSummaryRejectsContradictoryPayerNotificationAlongsideExpectedOutcome(t *testing.T) {
	starts := []events.Start{{EndToEndID: "tx-1", PayerISPB: "10000001", RequestStartedAtNS: 1_000_000_000, HTTPStatus: 200}}
	notifications := []events.Notification{
		{EndToEndID: "tx-1", ISPB: "10000001", EventType: events.EventPacs002Received, StatusCode: "ACSC", ReasonCodes: []string{}, ReceivedAtNS: 2_000_000_000},
		{EndToEndID: "tx-1", ISPB: "10000001", EventType: events.EventPacs002Received, StatusCode: "RJCT", ReasonCodes: []string{"AM04"}, ReceivedAtNS: 3_000_000_000},
	}

	summary := mustBuildSummary(t, starts, notifications, Options{SLAThresholdMs: 1_500})
	got := summary.Scenarios[0].Outcome
	if got.Matched != 1 || got.Missing != 0 || got.Contradictory != 1 || summary.Scenarios[0].Violations != 1 || summary.Valid {
		t.Fatalf("payer notification summary = %#v", got)
	}
	if summary.Performance.LatencyMs.P50 != 1000 || summary.Scenarios[0].Performance.WithinThreshold != 1 {
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

	got := summary.Scenarios[0].Outcome
	if got.Matched != 1 || got.Contradictory != 0 || summary.Scenarios[0].Violations != 0 {
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

	if summary.Performance.LatencyMs.P50 != 1000 {
		t.Fatalf("P50 = %f, want 1000", summary.Performance.LatencyMs.P50)
	}
	if summary.Scenarios[0].Performance.WithinThreshold != 1 {
		t.Fatalf("WithinThreshold = %d, want 1", summary.Scenarios[0].Performance.WithinThreshold)
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
		Duration:       5 * time.Second,
		Window:         reportTestWindow(10*time.Second, 5*time.Second),
	})

	if summary.Scenarios[0].Traffic.Payments.Started != 3 {
		t.Fatalf("full-run Started = %d, want 3", summary.Scenarios[0].Traffic.Payments.Started)
	}
	if summary.Performance.ActiveTPS.Payments != 0.2 {
		t.Fatalf("active-window throughput = %f, want 0.2", summary.Performance.ActiveTPS.Payments)
	}
}

func TestSummaryDoesNotShiftActiveWindowToDelayedFirstStart(t *testing.T) {
	options := Options{
		OfferedTxRate:         1,
		RequiredMinimumTxRate: 1,
		Duration:              5 * time.Second,
		Window: runwindow.Window{
			GenerationStartedAt: time.Unix(0, 0),
			ActiveStartedAt:     time.Unix(10, 0),
			GenerationEndedAt:   time.Unix(15, 0),
			ReplayDeadlineAt:    time.Unix(20, 0),
		},
	}
	starts := []events.Start{
		{EndToEndID: "late-first", PayerISPB: "10000001", ScenarioName: "happy-path", RequestStartedAtNS: time.Unix(12, 0).UnixNano(), HTTPStatus: 200},
		{EndToEndID: "at-end", PayerISPB: "10000002", ScenarioName: "happy-path", RequestStartedAtNS: time.Unix(15, 0).UnixNano(), HTTPStatus: 200},
	}

	summary := mustBuildSummary(t, starts, nil, options)
	if summary.Performance.ActiveTPS.Payments != 0.2 {
		t.Fatalf("active original rate = %f, want 0.2", summary.Performance.ActiveTPS.Payments)
	}
	if summary.Generation.OfferedTPS != 1 || summary.Generation.Started != 1 || summary.Generation.AverageTPS != 0.2 || summary.Generation.MinimumObservedTPS != 0 || summary.Generation.SustainedMinimumMet || summary.Generation.OutsideWindow != 1 || summary.Valid {
		t.Fatalf("load generation = %#v", summary.Generation)
	}
}

func TestSummaryReportsPacs002OriginalsAndSelectedReplays(t *testing.T) {
	statuses := []events.StatusStart{
		{EndToEndID: "tx-1", SenderISPB: "20000001", ScenarioName: "happy-path", RequestStartedAtNS: time.Unix(2, 0).UnixNano(), HTTPStatus: 200, Pacs002ReplaySelected: true},
		{EndToEndID: "tx-2", SenderISPB: "20000002", ScenarioName: "happy-path", RequestStartedAtNS: time.Unix(3, 0).UnixNano(), HTTPStatus: 500},
		{EndToEndID: "tx-3", SenderISPB: "20000003", ScenarioName: "happy-path", RequestStartedAtNS: time.Unix(30, 0).UnixNano(), HTTPStatus: 200},
	}
	replays := []events.Replay{
		{EndToEndID: "tx-1", SenderISPB: "20000001", ScenarioName: "happy-path", MessageType: events.MessagePacs002, RequestStartedAtNS: time.Unix(12, 0).UnixNano(), HTTPStatus: 202},
	}
	options := Options{
		Duration: 20 * time.Second,
		Replay:   config.Replay{Pacs002: &config.Pacs002Replay{Share: 0.10, Delay: 10 * time.Second}},
		Window: runwindow.Window{
			GenerationStartedAt: time.Unix(0, 0),
			ActiveStartedAt:     time.Unix(0, 0),
			GenerationEndedAt:   time.Unix(20, 0),
			ReplayDeadlineAt:    time.Unix(30, 0),
		},
	}
	summary, err := Build(nil, nil, statuses, replays, withDefaultScenario(options))
	if err != nil {
		t.Fatal(err)
	}
	if summary.Scenarios[0].Traffic.Pacs002.Started != 3 || summary.Scenarios[0].Traffic.Pacs002.Accepted != 2 || summary.Scenarios[0].Violations != 2 {
		t.Fatalf("PACS.002 originals = %#v, violations = %d", summary.Scenarios[0].Traffic.Pacs002, summary.Scenarios[0].Violations)
	}
	if summary.Replays.Pacs002.Started != 1 || summary.Replays.Pacs002.Accepted != 1 || summary.Replays.Pacs002.Violations != 0 {
		t.Fatalf("PACS.002 replays = %#v", summary.Replays.Pacs002)
	}
	if summary.Performance.ActiveTPS.Pacs002 != 0.1 || summary.Performance.ActiveTPS.Pacs002Replays != 0.05 {
		t.Fatalf("throughput = %#v", summary.Performance.ActiveTPS)
	}
}

func TestPacs002OriginalStartedDuringDrainCannotBeMarkedForReplay(t *testing.T) {
	window := runwindow.Window{
		GenerationStartedAt: time.Unix(0, 0),
		ActiveStartedAt:     time.Unix(0, 0),
		GenerationEndedAt:   time.Unix(10, 0),
		ReplayDeadlineAt:    time.Unix(30, 0),
	}
	statuses := []events.StatusStart{{
		EndToEndID:            "tx-in-drain",
		SenderISPB:            "20000001",
		ScenarioName:          "happy-path",
		RequestStartedAtNS:    time.Unix(11, 0).UnixNano(),
		HTTPStatus:            200,
		Pacs002ReplaySelected: true,
	}}
	replays := []events.Replay{{
		EndToEndID:         "tx-in-drain",
		SenderISPB:         "20000001",
		ScenarioName:       "happy-path",
		MessageType:        events.MessagePacs002,
		RequestStartedAtNS: time.Unix(21, 0).UnixNano(),
		HTTPStatus:         200,
	}}

	summary := summarizePacs002Replays(statuses, replays, &config.Pacs002Replay{
		Share: 0.05,
		Delay: 10 * time.Second,
	}, window)

	if summary.Started != 1 || summary.Accepted != 1 || summary.Violations != 1 {
		t.Fatalf("PACS.002 replay summary = %#v", summary)
	}
}

func TestSummaryReportsReplayCountsAndIngressRates(t *testing.T) {
	starts := []events.Start{
		{EndToEndID: "tx-1", PayerISPB: "10000001", ScenarioName: "happy-path", RequestStartedAtNS: 1_000_000_000, HTTPStatus: 200, Pacs008ReplaySelected: true},
		{EndToEndID: "tx-2", PayerISPB: "10000002", ScenarioName: "happy-path", RequestStartedAtNS: 6_000_000_000, HTTPStatus: 200, Pacs008ReplaySelected: true},
	}
	replays := []events.Replay{
		{EndToEndID: "tx-1", SenderISPB: "10000001", ScenarioName: "happy-path", MessageType: events.MessagePacs008, RequestStartedAtNS: 11_000_000_000, HTTPStatus: 200},
		{EndToEndID: "tx-2", SenderISPB: "10000002", ScenarioName: "happy-path", MessageType: events.MessagePacs008, RequestStartedAtNS: 16_000_000_000, HTTPStatus: 202},
	}
	options := Options{
		Duration: 20 * time.Second,
		Replay: config.Replay{Pacs008: &config.Pacs008Replay{
			Share: 0.10,
			Delay: 10 * time.Second,
		}},
	}
	summary := mustBuildSummaryWithReplays(t, starts, nil, replays, options)

	if summary.Replays.Pacs008.Started != 2 || summary.Replays.Pacs008.Accepted != 2 || summary.Replays.Pacs008.Violations != 0 {
		t.Fatalf("replay summary = %#v", summary.Replays.Pacs008)
	}
	if summary.Performance.ActiveTPS.Payments != 0.1 || summary.Performance.ActiveTPS.Pacs008Replays != 0.1 {
		t.Fatalf("throughput = %#v", summary.Performance.ActiveTPS)
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
		SenderISPB:         "10000001",
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
		{name: "metadata", starts: []events.Start{validStart}, replays: []events.Replay{func() events.Replay { value := validReplay; value.SenderISPB = "10000002"; return value }()}},
		{name: "http", starts: []events.Start{validStart}, replays: []events.Replay{func() events.Replay { value := validReplay; value.HTTPStatus = 500; return value }()}},
		{name: "early", starts: []events.Start{validStart}, replays: []events.Replay{func() events.Replay { value := validReplay; value.RequestStartedAtNS--; return value }()}},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			summary := mustBuildSummaryWithReplays(t, test.starts, nil, test.replays, options)
			if summary.Replays.Pacs008.Violations == 0 || summary.Valid {
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
		SLAThresholdMs:        4600,
		OfferedTxRate:         2,
		RequiredMinimumTxRate: 2,
		Duration:              2 * time.Second,
	})

	if summary.Generation.AverageTPS != 1.5 {
		t.Fatalf("Started throughput = %f, want 1.5", summary.Generation.AverageTPS)
	}
}

func TestSummaryIncludesOnlyReportRelevantConfiguration(t *testing.T) {
	summary := mustBuildSummary(t, nil, nil, Options{
		SLAThresholdMs:        1000,
		OfferedTxRate:         2000,
		RequiredMinimumTxRate: 2000,
		Duration:              180 * time.Second,
	})

	if summary.Generation.OfferedTPS != 2000 || summary.Generation.RollingWindowSeconds != 1 {
		t.Fatalf("Generation = %#v", summary.Generation)
	}
	if summary.Performance.ThresholdMs != 1000 {
		t.Fatalf("ThresholdMs = %d, want 1000", summary.Performance.ThresholdMs)
	}
}

func TestSummaryReportsPayerNotificationsOutsideActiveWindow(t *testing.T) {
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

	if summary.Performance.ActiveTPS.PayerNotifications != 1 {
		t.Fatalf("PayerNotifications = %f, want 1", summary.Performance.ActiveTPS.PayerNotifications)
	}
	if summary.Performance.PayerNotificationsAfterActive != 1 {
		t.Fatalf("PayerNotificationsAfterActive = %d, want 1", summary.Performance.PayerNotificationsAfterActive)
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
		Duration:       5 * time.Second,
		Window:         reportTestWindow(10*time.Second, 5*time.Second),
	})

	if summary.Scenarios[0].Traffic.Payments.Started != 4 {
		t.Fatalf("full-run Started = %d, want 4", summary.Scenarios[0].Traffic.Payments.Started)
	}
	if summary.Scenarios[0].Traffic.Payments.Accepted != 4 {
		t.Fatalf("full-run Accepted = %d, want 4", summary.Scenarios[0].Traffic.Payments.Accepted)
	}
	if summary.Scenarios[0].Performance.WithinThreshold != 1 {
		t.Fatalf("WithinThreshold = %d, want 1", summary.Scenarios[0].Performance.WithinThreshold)
	}
	if summary.Scenarios[0].Performance.AfterThreshold != 1 {
		t.Fatalf("AfterThreshold = %d, want 1", summary.Scenarios[0].Performance.AfterThreshold)
	}
	if summary.Performance.ActiveTPS.Payments != 0.4 {
		t.Fatalf("Started throughput = %f, want 0.4", summary.Performance.ActiveTPS.Payments)
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

	if summary.Scenarios[0].Traffic.Payments.Accepted != 1 || summary.Scenarios[0].Outcome.Matched != 1 {
		t.Fatalf("Scenario = %#v", summary.Scenarios[0])
	}
}

func TestSummaryRejectsUnknownScenarioName(t *testing.T) {
	_, err := Build([]events.Start{{
		EndToEndID:   "tx-1",
		ScenarioName: "not-configured",
	}}, nil, nil, nil, Options{Scenarios: []config.Scenario{reportTestHappyPathScenario()}})

	if err == nil {
		t.Fatal("Build accepted unknown scenario name")
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

	if len(summary.Scenarios) != 2 || summary.Scenarios[0].Name != "happy-path" || summary.Scenarios[1].Name != "insufficient-funds" {
		t.Fatalf("ordered scenarios = %#v", summary.Scenarios)
	}
	happy := summary.Scenarios[0]
	if happy.Traffic.Payments.Started != 2 || happy.Traffic.Payments.Accepted != 2 || happy.Outcome.Matched != 1 || happy.Outcome.Missing != 1 || happy.Violations != 1 {
		t.Fatalf("happy-path summary = %#v", happy)
	}
	insufficient := summary.Scenarios[1]
	if insufficient.Traffic.Payments.Started != 3 || insufficient.Traffic.Payments.Accepted != 2 || insufficient.Outcome.Matched != 1 || insufficient.Outcome.Missing != 1 {
		t.Fatalf("insufficient-funds counts = %#v", insufficient)
	}
	if insufficient.Violations != 2 || summary.Valid {
		t.Fatalf("insufficient-funds violations = %#v", insufficient)
	}
}

func TestSummaryRejectsUntaggedStartsForMixedProfile(t *testing.T) {
	_, err := Build([]events.Start{{EndToEndID: "untagged", HTTPStatus: 200}}, nil, nil, nil, Options{
		Scenarios: []config.Scenario{reportTestHappyPathScenario(), reportTestInsufficientFundsScenario()},
	})
	if err == nil || err.Error() == "" {
		t.Fatal("mixed report accepted an untagged start")
	}
}

func TestSummaryRejectsUntaggedStartForSingleScenario(t *testing.T) {
	_, err := Build([]events.Start{{EndToEndID: "untagged", HTTPStatus: 200}}, nil, nil, nil, Options{
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
	options = withDefaultWindow(options)
	summary, err := Build(starts, notifications, nil, replays, options)
	if err != nil {
		t.Fatal(err)
	}
	return summary
}

func withDefaultScenario(options Options) Options {
	if len(options.Scenarios) == 0 {
		options.Scenarios = []config.Scenario{reportTestHappyPathScenario()}
	}
	return options
}

func withDefaultWindow(options Options) Options {
	if options.Window.GenerationStartedAt.IsZero() {
		options.Window = reportTestWindow(0, options.Duration)
	}
	return options
}

func reportTestWindow(warmup, duration time.Duration) runwindow.Window {
	started := time.Unix(0, 0)
	generationEnded := started.Add(warmup + duration)
	if duration <= 0 {
		generationEnded = started.Add(warmup + 24*time.Hour)
	}
	return runwindow.Window{
		GenerationStartedAt: started,
		ActiveStartedAt:     started.Add(warmup),
		GenerationEndedAt:   generationEnded,
		ReplayDeadlineAt:    generationEnded.Add(24 * time.Hour),
	}
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
