package runwindow

import (
	"path/filepath"
	"testing"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/config"
)

func TestNewUsesSimulatorClockAndEndsAfterConfiguredDrain(t *testing.T) {
	started := time.Date(2026, 8, 11, 12, 0, 0, 123, time.UTC)
	document := New("mixed-outcomes-smoke", started, 5*time.Second, 10*time.Second, 10*time.Second, config.Replay{
		Pacs008: &config.Pacs008Replay{Delay: 7 * time.Second},
		Pacs002: &config.Pacs002Replay{Delay: 9 * time.Second},
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
	if !document.Window.ReplayDeadlineAt.Equal(started.Add(25 * time.Second)) {
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
