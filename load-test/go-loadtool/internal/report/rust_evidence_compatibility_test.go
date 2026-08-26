package report

import (
	"bytes"
	"encoding/json"
	"path/filepath"
	"testing"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/config"
	"instant-payment-system/load-test/go-loadtool/internal/runwindow"
)

func TestPrintConsumesRustEvidenceContract(t *testing.T) {
	root := filepath.Join("testdata", "rust-evidence")
	var output bytes.Buffer
	options := Options{
		SLAThresholdMs: 1_000,
		Duration:       time.Second,
		Scenarios:      []config.Scenario{reportTestHappyPathScenario()},
		Window: runwindow.Window{
			GenerationStartedAt: time.Unix(9, 0),
			WarmupEndedAt:       time.Unix(10, 0),
			ActiveStartedAt:     time.Unix(10, 0),
			GenerationEndedAt:   time.Unix(11, 0),
			ReplayDeadlineAt:    time.Unix(12, 0),
		},
	}

	err := Print(
		filepath.Join(root, "pacs008-starts.csv"),
		filepath.Join(root, "notifications.csv"),
		filepath.Join(root, "pacs002-starts.csv"),
		filepath.Join(root, "replays.csv"),
		options,
		&output,
	)
	if err != nil {
		t.Fatalf("Print Rust evidence: %v", err)
	}

	var summary Summary
	if err := json.Unmarshal(output.Bytes(), &summary); err != nil {
		t.Fatalf("decode report: %v", err)
	}
	if len(summary.Scenarios) != 1 || summary.Scenarios[0].Outcome.Matched != 1 {
		t.Fatalf("Rust evidence outcome = %#v", summary.Scenarios)
	}
	if summary.Scenarios[0].Traffic.Payments.Started != 1 || summary.Scenarios[0].Traffic.Pacs002.Started != 1 {
		t.Fatalf("Rust traffic was not preserved: %#v", summary.Scenarios[0].Traffic)
	}
}
