package report

import (
	"fmt"
	"math/rand"
	"sort"
	"testing"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/config"
	"instant-payment-system/load-test/go-loadtool/internal/events"
	"instant-payment-system/load-test/go-loadtool/internal/runwindow"
)

func TestGenerationUsesRollingWindowsInsteadOfAlignedSeconds(t *testing.T) {
	start := time.Unix(100, 0)
	options := rollingGenerationOptions(start, 2*time.Second, 2)
	starts := startsAt(start,
		1_950*time.Millisecond,
		0,
		1_900*time.Millisecond,
		100*time.Millisecond,
	)

	summary := summarizeGeneration(starts, options)

	if summary.Started != 4 || summary.AverageTPS != 2 {
		t.Fatalf("generation totals = %#v", summary)
	}
	if summary.MinimumObservedTPS != 0 || summary.SustainedMinimumMet {
		t.Fatalf("rolling minimum = %#v, want minimum 0 and unmet target", summary)
	}
	if summary.MaximumObservedTPS != 2 {
		t.Fatalf("rolling maximum = %d, want 2", summary.MaximumObservedTPS)
	}
}

func TestGenerationAllowsPeaksWhenEveryRollingWindowMeetsMinimum(t *testing.T) {
	start := time.Unix(100, 0)
	options := rollingGenerationOptions(start, 2*time.Second, 2)
	starts := startsAt(start,
		1_500*time.Millisecond,
		250*time.Millisecond,
		0,
		time.Second,
		500*time.Millisecond,
	)

	summary := summarizeGeneration(starts, options)

	if summary.MinimumObservedTPS != 2 || summary.MaximumObservedTPS != 3 {
		t.Fatalf("rolling range = %#v, want minimum 2 and maximum 3", summary)
	}
	if !summary.SustainedMinimumMet {
		t.Fatalf("sustained minimum was not met: %#v", summary)
	}
}

func TestGenerationValidatesRequiredMinimumBelowOfferedRate(t *testing.T) {
	start := time.Unix(100, 0)
	options := Options{
		OfferedTxRate:         3,
		RequiredMinimumTxRate: 2,
		Duration:              2 * time.Second,
		Window: runwindow.Window{
			GenerationStartedAt: start,
			ActiveStartedAt:     start,
			GenerationEndedAt:   start.Add(2 * time.Second),
			ReplayDeadlineAt:    start.Add(3 * time.Second),
		},
	}
	starts := startsAt(start,
		0,
		500*time.Millisecond,
		time.Second,
		1500*time.Millisecond,
	)

	summary := summarizeGeneration(starts, options)

	if summary.OfferedTPS != 3 || summary.RequiredMinimumTPS != 2 {
		t.Fatalf("configured rates = %#v, want offered=3 required=2", summary)
	}
	if summary.MinimumObservedTPS != 2 || !summary.SustainedMinimumMet {
		t.Fatalf("generation = %#v, want required minimum to be met", summary)
	}
}

func TestGenerationRollingWindowUsesSemiOpenBoundaries(t *testing.T) {
	start := time.Unix(100, 0)
	options := rollingGenerationOptions(start, time.Second, 2)
	starts := startsAt(start,
		0,
		time.Second-time.Nanosecond,
	)
	starts = append(starts,
		events.Start{RequestStartedAtNS: start.Add(-time.Nanosecond).UnixNano()},
		events.Start{RequestStartedAtNS: start.Add(time.Second).UnixNano()},
	)

	summary := summarizeGeneration(starts, options)

	if summary.Started != 2 || summary.MinimumObservedTPS != 2 || summary.MaximumObservedTPS != 2 {
		t.Fatalf("semi-open rolling window = %#v", summary)
	}
	if summary.OutsideWindow != 2 {
		t.Fatalf("outside window = %d, want 2", summary.OutsideWindow)
	}
}

func TestGenerationWithNoActiveStartsDoesNotMeetMinimum(t *testing.T) {
	start := time.Unix(100, 0)
	options := rollingGenerationOptions(start, time.Second, 2)

	summary := summarizeGeneration(nil, options)

	if summary.MinimumObservedTPS != 0 || summary.MaximumObservedTPS != 0 || summary.SustainedMinimumMet {
		t.Fatalf("empty generation = %#v", summary)
	}
}

func TestRollingCountMatchesExhaustiveWindowScan(t *testing.T) {
	random := rand.New(rand.NewSource(42))
	for sample := 0; sample < 200; sample++ {
		var timestamps []int64
		for instant := int64(0); instant < 20; instant++ {
			for range random.Intn(3) {
				timestamps = append(timestamps, instant)
			}
		}
		sort.Slice(timestamps, func(left, right int) bool { return timestamps[left] < timestamps[right] })

		wantMinimum, wantMaximum := len(timestamps), 0
		for windowStart := int64(0); windowStart <= 10; windowStart++ {
			count := 0
			for _, timestamp := range timestamps {
				if timestamp >= windowStart && timestamp < windowStart+10 {
					count++
				}
			}
			wantMinimum = min(wantMinimum, count)
			wantMaximum = max(wantMaximum, count)
		}

		if got := minimumRollingCount(timestamps, 0, 20, 10); got != wantMinimum {
			t.Fatalf("sample %d minimum = %d, want %d for %v", sample, got, wantMinimum, timestamps)
		}
		if got := maximumRollingCount(timestamps, 0, 20, 10); got != wantMaximum {
			t.Fatalf("sample %d maximum = %d, want %d for %v", sample, got, wantMaximum, timestamps)
		}
	}
}

func TestSummaryAllowsOverTargetWhenRollingMinimumAndSLAAreMet(t *testing.T) {
	start := time.Unix(100, 0)
	options := rollingGenerationOptions(start, 2*time.Second, 2)
	options.SLAThresholdMs = 1_000
	options.Scenarios = []config.Scenario{reportTestHappyPathScenario()}
	starts, notifications := successfulPayments(start,
		0,
		250*time.Millisecond,
		500*time.Millisecond,
		time.Second,
		1_500*time.Millisecond,
	)

	summary, err := Build(starts, notifications, nil, nil, options)
	if err != nil {
		t.Fatal(err)
	}

	if !summary.Generation.SustainedMinimumMet || !summary.Performance.WithinSLA || !summary.Valid {
		t.Fatalf("over-target sustained run was rejected: %#v", summary)
	}
}

func TestSummaryRejectsP99AboveConfiguredSLA(t *testing.T) {
	start := time.Unix(100, 0)
	options := rollingGenerationOptions(start, time.Second, 2)
	options.SLAThresholdMs = 1_000
	options.Scenarios = []config.Scenario{reportTestHappyPathScenario()}
	starts, notifications := successfulPayments(start, 0, 500*time.Millisecond)
	for index := range notifications {
		notifications[index].ReceivedAtNS += 2 * time.Second.Nanoseconds()
	}

	summary, err := Build(starts, notifications, nil, nil, options)
	if err != nil {
		t.Fatal(err)
	}

	if summary.Performance.WithinSLA || summary.Valid {
		t.Fatalf("run with p99 above SLA was accepted: %#v", summary)
	}
}

func rollingGenerationOptions(activeStart time.Time, duration time.Duration, target int) Options {
	return Options{
		OfferedTxRate:         target,
		RequiredMinimumTxRate: target,
		Duration:              duration,
		Window: runwindow.Window{
			GenerationStartedAt: activeStart,
			ActiveStartedAt:     activeStart,
			GenerationEndedAt:   activeStart.Add(duration),
			ReplayDeadlineAt:    activeStart.Add(duration + time.Second),
		},
	}
}

func startsAt(start time.Time, offsets ...time.Duration) []events.Start {
	starts := make([]events.Start, len(offsets))
	for index, offset := range offsets {
		starts[index] = events.Start{RequestStartedAtNS: start.Add(offset).UnixNano()}
	}
	return starts
}

func successfulPayments(start time.Time, offsets ...time.Duration) ([]events.Start, []events.Notification) {
	starts := make([]events.Start, len(offsets))
	notifications := make([]events.Notification, len(offsets))
	for index, offset := range offsets {
		endToEndID := fmt.Sprintf("tx-%d", index)
		startedAt := start.Add(offset)
		starts[index] = events.Start{
			EndToEndID:         endToEndID,
			PayerISPB:          "10000001",
			ScenarioName:       "happy-path",
			RequestStartedAtNS: startedAt.UnixNano(),
			HTTPStatus:         200,
		}
		notifications[index] = events.Notification{
			EndToEndID:   endToEndID,
			ISPB:         "10000001",
			EventType:    events.EventPacs002Received,
			StatusCode:   "ACSC",
			ReasonCodes:  []string{},
			ReceivedAtNS: startedAt.Add(100 * time.Millisecond).UnixNano(),
		}
	}
	return starts, notifications
}
