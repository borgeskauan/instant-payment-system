package sim

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/config"
)

func TestWarmupOutcomeCompletesOnceAndAcceptsAtLeastOnceDuplicate(t *testing.T) {
	tracker := newPhaseTracker()
	if err := tracker.Add(); err != nil {
		t.Fatal(err)
	}
	tracker.CloseGeneration()
	s := &simulator{
		cfg: configForOutcomeTest("ACSC", nil),
		paymentStates: map[string]paymentState{"tx-1": {
			payerISPB:    "10000001",
			scenarioName: "happy-path",
			tracker:      tracker,
		}},
	}

	s.observePayerOutcome("tx-1", "10000001", "ACSC", nil)
	s.observePayerOutcome("tx-1", "10000001", "ACSC", nil)
	if err := tracker.Wait(context.Background()); err != nil {
		t.Fatalf("Wait() error = %v", err)
	}
	if len(s.paymentStates) != 0 {
		t.Fatalf("pending payment states = %d, want 0", len(s.paymentStates))
	}
}

func TestWarmupContradictoryOutcomeFailsPhase(t *testing.T) {
	tracker := newPhaseTracker()
	if err := tracker.Add(); err != nil {
		t.Fatal(err)
	}
	tracker.CloseGeneration()
	s := &simulator{
		cfg: configForOutcomeTest("RJCT", []string{"AM04"}),
		paymentStates: map[string]paymentState{"tx-1": {
			payerISPB:    "10000001",
			scenarioName: "happy-path",
			tracker:      tracker,
		}},
	}

	s.observePayerOutcome("tx-1", "10000001", "ACSC", nil)
	if err := tracker.Wait(context.Background()); err == nil || !strings.Contains(err.Error(), "contradictory payer outcome") {
		t.Fatalf("Wait() error = %v", err)
	}
	if len(s.paymentStates) != 1 {
		t.Fatalf("pending payment states = %d, want contradictory outcome retained", len(s.paymentStates))
	}
}

func TestActivePayerOutcomeReleasesPaymentState(t *testing.T) {
	s := &simulator{
		cfg: configForOutcomeTest("ACSC", nil),
		paymentStates: map[string]paymentState{"tx-1": {
			payerISPB:    "10000001",
			scenarioName: "happy-path",
		}},
	}

	s.observePayerOutcome("tx-1", "10000001", "ACSC", nil)

	if len(s.paymentStates) != 0 {
		t.Fatalf("pending payment states = %d, want 0", len(s.paymentStates))
	}
}

func configForOutcomeTest(status string, reasons []string) Config {
	return Config{Scenarios: []config.Scenario{{
		Name: "happy-path",
		Expectations: config.ScenarioExpectations{PayerNotification: config.PayerNotificationExpectation{
			Status:      status,
			ReasonCodes: reasons,
		}},
	}}}
}

func TestPhaseTrackerWaitsForGenerationCloseAndPendingWork(t *testing.T) {
	tracker := newPhaseTracker()
	if err := tracker.Add(); err != nil {
		t.Fatal(err)
	}

	result := make(chan error, 1)
	go func() { result <- tracker.Wait(context.Background()) }()
	assertTrackerStillWaiting(t, result)

	tracker.CloseGeneration()
	assertTrackerStillWaiting(t, result)

	if err := tracker.Done(); err != nil {
		t.Fatal(err)
	}
	if err := <-result; err != nil {
		t.Fatalf("Wait() error = %v", err)
	}
}

func TestPhaseTrackerContinuationRegisteredBeforeParentDoneKeepsGateClosed(t *testing.T) {
	tracker := newPhaseTracker()
	if err := tracker.Add(); err != nil {
		t.Fatal(err)
	}
	tracker.CloseGeneration()

	if err := tracker.Add(); err != nil {
		t.Fatal(err)
	}
	if err := tracker.Done(); err != nil {
		t.Fatal(err)
	}

	result := make(chan error, 1)
	go func() { result <- tracker.Wait(context.Background()) }()
	assertTrackerStillWaiting(t, result)

	if err := tracker.Done(); err != nil {
		t.Fatal(err)
	}
	if err := <-result; err != nil {
		t.Fatalf("Wait() error = %v", err)
	}
}

func TestPhaseTrackerRejectsWorkRegisteredAfterCompletion(t *testing.T) {
	tracker := newPhaseTracker()
	tracker.CloseGeneration()
	if err := tracker.Wait(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := tracker.Add(); err == nil || !strings.Contains(err.Error(), "completed") {
		t.Fatalf("Add() error = %v, want completed tracker rejection", err)
	}
}

func TestPhaseTrackerFailureWakesWaiter(t *testing.T) {
	tracker := newPhaseTracker()
	if err := tracker.Add(); err != nil {
		t.Fatal(err)
	}
	result := make(chan error, 1)
	go func() { result <- tracker.Wait(context.Background()) }()

	want := errors.New("warmup request failed")
	tracker.Fail(want)
	if err := <-result; !errors.Is(err, want) {
		t.Fatalf("Wait() error = %v, want %v", err, want)
	}
}

func TestPhaseTrackerWaitHonorsContextDeadline(t *testing.T) {
	tracker := newPhaseTracker()
	if err := tracker.Add(); err != nil {
		t.Fatal(err)
	}
	tracker.CloseGeneration()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Millisecond)
	defer cancel()
	if err := tracker.Wait(ctx); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("Wait() error = %v, want deadline exceeded", err)
	}
}

func assertTrackerStillWaiting(t *testing.T, result <-chan error) {
	t.Helper()
	select {
	case err := <-result:
		t.Fatalf("Wait() returned early: %v", err)
	case <-time.After(10 * time.Millisecond):
	}
}
