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
		cfg:               configForOutcomeTest("ACSC", nil),
		transferScenarios: map[string]string{"tx-1": "happy-path"},
		transferPayers:    map[string]string{"tx-1": "10000001"},
		transferTrackers:  map[string]*phaseTracker{"tx-1": tracker},
		completedOutcomes: make(map[string]struct{}),
	}

	s.observeWarmupOutcome("tx-1", "10000001", "ACSC", nil)
	s.observeWarmupOutcome("tx-1", "10000001", "ACSC", nil)
	if err := tracker.Wait(context.Background()); err != nil {
		t.Fatalf("Wait() error = %v", err)
	}
}

func TestWarmupContradictoryOutcomeFailsPhase(t *testing.T) {
	tracker := newPhaseTracker()
	if err := tracker.Add(); err != nil {
		t.Fatal(err)
	}
	tracker.CloseGeneration()
	s := &simulator{
		cfg:               configForOutcomeTest("RJCT", []string{"AM04"}),
		transferScenarios: map[string]string{"tx-1": "happy-path"},
		transferPayers:    map[string]string{"tx-1": "10000001"},
		transferTrackers:  map[string]*phaseTracker{"tx-1": tracker},
		completedOutcomes: make(map[string]struct{}),
	}

	s.observeWarmupOutcome("tx-1", "10000001", "ACSC", nil)
	if err := tracker.Wait(context.Background()); err == nil || !strings.Contains(err.Error(), "contradictory payer outcome") {
		t.Fatalf("Wait() error = %v", err)
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
