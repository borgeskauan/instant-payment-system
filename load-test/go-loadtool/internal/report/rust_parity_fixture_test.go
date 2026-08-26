package report

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"testing"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/config"
	"instant-payment-system/load-test/go-loadtool/internal/events"
	"instant-payment-system/load-test/go-loadtool/internal/pullmetrics"
	"instant-payment-system/load-test/go-loadtool/internal/runwindow"
)

func TestRustParityFixture(t *testing.T) {
	root := filepath.Join("..", "..", "..", "testdata", "report-parity")
	starts, err := events.ReadStarts(filepath.Join(root, "events", "pacs008-starts.csv"))
	if err != nil {
		t.Fatal(err)
	}
	statuses, err := events.ReadStatusStarts(filepath.Join(root, "events", "pacs002-starts.csv"))
	if err != nil {
		t.Fatal(err)
	}
	notifications, err := events.ReadNotifications(filepath.Join(root, "events", "notifications.csv"))
	if err != nil {
		t.Fatal(err)
	}
	replays, err := events.ReadReplays(filepath.Join(root, "events", "replays.csv"))
	if err != nil {
		t.Fatal(err)
	}
	windowDocument, err := runwindow.Read(filepath.Join(root, "run-window.json"))
	if err != nil {
		t.Fatal(err)
	}

	pull := pullmetrics.Snapshot{EmptyResponses: 1}
	pull.Batches[1] = 5
	pull.Batches[2] = 1
	options := Options{
		SLAThresholdMs:        1000,
		OfferedTxRate:         2,
		RequiredMinimumTxRate: 1,
		Duration:              2 * time.Second,
		Replay: config.Replay{
			Pacs008: &config.Pacs008Replay{Share: 0.5, Delay: time.Second},
			Pacs002: &config.Pacs002Replay{Share: 0.5, Delay: time.Second},
		},
		Scenarios: []config.Scenario{
			{
				Name: "happy-path", Share: 0.5,
				Expectations: config.ScenarioExpectations{
					HTTPStatus: config.ExpectedHTTP2xx,
					PayerNotification: config.PayerNotificationExpectation{
						DeliverySemantics: config.DeliveryAtLeastOnce,
						Status:            "ACSC", ReasonCodes: []string{},
					},
				},
			},
			{
				Name: "insufficient-funds", Share: 0.5,
				Expectations: config.ScenarioExpectations{
					HTTPStatus: config.ExpectedHTTP2xx,
					PayerNotification: config.PayerNotificationExpectation{
						DeliverySemantics: config.DeliveryAtLeastOnce,
						Status:            "RJCT", ReasonCodes: []string{"AM04"},
					},
				},
			},
		},
		Window:           windowDocument.Window,
		NotificationPull: pull,
	}
	actual, err := Build(starts, notifications, statuses, replays, options)
	if err != nil {
		t.Fatal(err)
	}
	actualJSON, err := json.MarshalIndent(actual, "", "  ")
	if err != nil {
		t.Fatal(err)
	}

	expectedJSON, err := os.ReadFile(filepath.Join(root, "expected-sla-report.json"))
	if err != nil {
		t.Fatal(err)
	}
	var expectedValue, actualValue any
	if err := json.Unmarshal(expectedJSON, &expectedValue); err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(actualJSON, &actualValue); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(actualValue, expectedValue) {
		t.Fatalf("fixture mismatch; actual:\n%s\n", actualJSON)
	}
}
