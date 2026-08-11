package runwindow

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/config"
)

func TestNewUsesSimulatorClockAndMaximumEnabledReplayDelay(t *testing.T) {
	started := time.Date(2026, 8, 11, 12, 0, 0, 123, time.UTC)
	document := New("mixed-outcomes-smoke", started, 5*time.Second, 10*time.Second, 10*time.Second, config.Replay{
		Pacs008: &config.Pacs008Replay{Delay: 7 * time.Second},
		Pacs002: &config.Pacs002Replay{Delay: 11 * time.Second},
	})

	if !document.Window.GenerationStartedAt.Equal(started) {
		t.Fatalf("generation start = %s", document.Window.GenerationStartedAt)
	}
	if !document.Window.ActiveStartedAt.Equal(started.Add(5 * time.Second)) {
		t.Fatalf("active start = %s", document.Window.ActiveStartedAt)
	}
	if !document.Window.GenerationEndedAt.Equal(started.Add(15 * time.Second)) {
		t.Fatalf("generation end = %s", document.Window.GenerationEndedAt)
	}
	if !document.Window.ReplayDeadlineAt.Equal(started.Add(36 * time.Second)) {
		t.Fatalf("replay deadline = %s", document.Window.ReplayDeadlineAt)
	}
}

func TestDocumentRoundTripPreservesNanoseconds(t *testing.T) {
	started := time.Date(2026, 8, 11, 12, 0, 0, 123456789, time.UTC)
	want := New("mixed-outcomes-smoke", started, time.Second, 2*time.Second, 3*time.Second, config.Replay{})
	path := filepath.Join(t.TempDir(), "run-window.json")
	if err := Write(path, want); err != nil {
		t.Fatal(err)
	}
	got, err := Read(path)
	if err != nil {
		t.Fatal(err)
	}
	if got.SchemaVersion != 2 || got.Profile.Name != "mixed-outcomes-smoke" || !got.Window.GenerationStartedAt.Equal(started) || !got.Window.ReplayDeadlineAt.Equal(started.Add(6*time.Second)) {
		t.Fatalf("round trip = %#v", got)
	}
}

func TestValidateRejectsWindowThatDriftsFromProfile(t *testing.T) {
	document := New("mixed-outcomes-smoke", time.Unix(100, 0), time.Second, 2*time.Second, 3*time.Second, config.Replay{})
	document.Window.ActiveStartedAt = document.Window.ActiveStartedAt.Add(time.Second)
	if err := Validate(document, "mixed-outcomes-smoke", time.Second, 2*time.Second, 3*time.Second, config.Replay{}); err == nil {
		t.Fatal("Validate accepted a shifted active window")
	}
}

func TestReadAcceptsRunnerEnrichmentWithoutChangingAuthoritativeWindow(t *testing.T) {
	path := filepath.Join(t.TempDir(), "run-window.json")
	data := `{
  "schema_version": 2,
  "tag": "functional-smoke",
  "result_dir": "results/functional-smoke/20260811_120000",
  "profile": {"name": "mixed-outcomes-smoke", "snapshot": "profile.json", "execution_plan": "execution-plan.json"},
  "artifacts": {"starts": "go-loadtool/starts.csv", "status_starts": "go-loadtool/status-starts.csv"},
  "window": {
    "generation_started_at": "2026-08-11T12:00:00.123456789Z",
    "active_started_at": "2026-08-11T12:00:05.123456789Z",
    "generation_ended_at": "2026-08-11T12:00:15.123456789Z",
    "replay_deadline_at": "2026-08-11T12:00:35.123456789Z",
    "run_started_at": "2026-08-11T11:59:00Z",
    "drain_finished_at": "2026-08-11T12:00:35.223456789Z"
  },
  "grafana": {"available_at_run_start": true, "base_url": "http://localhost:3000"}
}`
	if err := os.WriteFile(path, []byte(data), 0o644); err != nil {
		t.Fatal(err)
	}
	document, err := Read(path)
	if err != nil {
		t.Fatal(err)
	}
	if got := document.Window.GenerationStartedAt.Nanosecond(); got != 123456789 {
		t.Fatalf("generation-start nanoseconds = %d", got)
	}
}

func TestResolveUsesStoredLegacyWindowWithoutInspectingEventTimes(t *testing.T) {
	path := filepath.Join(t.TempDir(), "run-window.json")
	data := `{
  "tag": "pacs008-replay-functional-smoke",
  "result_dir": "results/pacs008-replay-functional-smoke/20260811_020918",
  "profile": {"name": "mixed-outcomes-smoke", "snapshot": "profile.json", "execution_plan": "execution-plan.json"},
  "artifacts": {"starts": "go-loadtool/starts.csv", "events": "go-loadtool/events.csv", "replays": "go-loadtool/replays.csv", "report": "sla-report.json"},
  "window": {
    "run_started_at": "2026-08-11T02:09:18-03:00",
    "active_started_at": "2026-08-11T02:09:23-03:00",
    "active_finished_at": "2026-08-11T02:09:33-03:00",
    "drain_finished_at": "2026-08-11T02:10:22-03:00"
  },
  "grafana": {"available_at_run_start": false, "base_url": "http://localhost:3000", "full_run_url": "x", "active_window_url": "y"}
}`
	if err := os.WriteFile(path, []byte(data), 0o644); err != nil {
		t.Fatal(err)
	}
	document, err := Read(path)
	if err != nil {
		t.Fatal(err)
	}
	window, err := Resolve(document, "mixed-outcomes-smoke", 5*time.Second, 10*time.Second, 10*time.Second, config.Replay{})
	if err != nil {
		t.Fatal(err)
	}
	if !window.GenerationStartedAt.Equal(time.Date(2026, 8, 11, 2, 9, 18, 0, time.FixedZone("-03", -3*60*60))) || !window.GenerationEndedAt.Equal(time.Date(2026, 8, 11, 2, 9, 33, 0, time.FixedZone("-03", -3*60*60))) || !window.ReplayDeadlineAt.Equal(time.Date(2026, 8, 11, 2, 10, 22, 0, time.FixedZone("-03", -3*60*60))) {
		t.Fatalf("resolved legacy window = %#v", window)
	}
}
