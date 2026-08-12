package report

import (
	"testing"

	"instant-payment-system/load-test/go-loadtool/internal/config"
	"instant-payment-system/load-test/go-loadtool/internal/events"
)

func TestMixedOutcomesCharacterizesOneLogicalFinalResultPerPayment(t *testing.T) {
	scenarios := []config.Scenario{reportTestHappyPathScenario(), reportTestInsufficientFundsScenario()}
	scenarios[0].Share = 0.8
	starts := []events.Start{
		{EndToEndID: "happy", PayerISPB: "10000001", HTTPStatus: 200, ScenarioName: "happy-path"},
		{EndToEndID: "insufficient", PayerISPB: "10000041", HTTPStatus: 202, ScenarioName: "insufficient-funds"},
	}
	notifications := []events.Notification{
		{EndToEndID: "happy", ISPB: "10000001", EventType: events.EventPacs002Received, StatusCode: "ACSC", ReasonCodes: []string{}, ReceivedAtNS: 1_000_000},
		{EndToEndID: "happy", ISPB: "10000001", EventType: events.EventPacs002Received, StatusCode: "ACSC", ReasonCodes: []string{}, ReceivedAtNS: 2_000_000},
		{EndToEndID: "insufficient", ISPB: "10000041", EventType: events.EventPacs002Received, StatusCode: "RJCT", ReasonCodes: []string{"AM04"}, ReceivedAtNS: 3_000_000},
		{EndToEndID: "insufficient", ISPB: "10000041", EventType: events.EventPacs002Received, StatusCode: "RJCT", ReasonCodes: []string{"AM04"}, ReceivedAtNS: 4_000_000},
	}

	summary := mustBuildSummary(t, starts, notifications, Options{Scenarios: scenarios})

	if len(summary.Scenarios) != 2 {
		t.Fatalf("scenarios = %#v, want two", summary.Scenarios)
	}
	for _, scenario := range summary.Scenarios {
		outcome := scenario.Outcome
		if scenario.Traffic.Payments.Started != 1 || scenario.Traffic.Payments.Accepted != 1 || outcome.Matched != 1 || outcome.Missing != 0 || outcome.Contradictory != 0 || scenario.Violations != 0 {
			t.Fatalf("scenario %q logical outcome = %#v, want one matching final result", scenario.Name, outcome)
		}
	}
	if !summary.Valid {
		t.Fatalf("valid = false, summary=%#v", summary)
	}
}
