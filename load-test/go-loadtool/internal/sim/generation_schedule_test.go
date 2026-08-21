package sim

import (
	"context"
	"testing"
	"time"
)

func TestOriginalSlotScheduleDoesNotDriftForRatesThatDoNotDivideOneSecond(t *testing.T) {
	start := time.Unix(100, 0)
	want := []time.Time{
		start,
		start.Add(333_333_333 * time.Nanosecond),
		start.Add(666_666_666 * time.Nanosecond),
		start.Add(time.Second),
	}

	for index, wantScheduledAt := range want {
		if got := originalSlotScheduledAt(start, 3, uint64(index)); !got.Equal(wantScheduledAt) {
			t.Fatalf("slot %d scheduled at %s, want %s", index, got, wantScheduledAt)
		}
	}
}

func TestFirstUnexpiredOriginalSlotJumpsOverLongExpiredPrefix(t *testing.T) {
	start := time.Unix(100, 0)
	now := start.Add(49*time.Second + 123*time.Millisecond)

	index := firstUnexpiredOriginalSlot(start, 2_000, now, 10*time.Millisecond)

	if index != 98_227 {
		t.Fatalf("first unexpired slot = %d, want 98227", index)
	}
	scheduledAt := originalSlotScheduledAt(start, 2_000, index)
	if !now.Before(scheduledAt.Add(10 * time.Millisecond)) {
		t.Fatalf("slot %d deadline %s is not after now %s", index, scheduledAt.Add(10*time.Millisecond), now)
	}
	previous := originalSlotScheduledAt(start, 2_000, index-1)
	if now.Before(previous.Add(10 * time.Millisecond)) {
		t.Fatalf("previous slot %d deadline %s is still valid at %s", index-1, previous.Add(10*time.Millisecond), now)
	}
}

func TestOriginalSlotDeadlineNeverCrossesPhaseBoundary(t *testing.T) {
	phaseEnd := time.Unix(200, 0)
	scheduledAt := phaseEnd.Add(-time.Millisecond)

	deadline := originalSlotDeadline(scheduledAt, phaseEnd, 10*time.Millisecond)

	if !deadline.Equal(phaseEnd) {
		t.Fatalf("deadline = %s, want phase end %s", deadline, phaseEnd)
	}
	if originalSlotCanStart(phaseEnd, deadline) {
		t.Fatal("slot was allowed to start at the exclusive phase boundary")
	}
}

func TestOriginalPhaseHasExactConfiguredSlotCount(t *testing.T) {
	if got := originalPhaseSlotCount(3, 2*time.Second); got != 6 {
		t.Fatalf("phase slots = %d, want 6", got)
	}
	if got := originalPhaseSlotCount(2_000, time.Minute); got != 120_000 {
		t.Fatalf("phase slots = %d, want 120000", got)
	}
}

func TestOriginalPhaseDoesNotQueueSlotPastItsDeadline(t *testing.T) {
	phaseStart := time.Now().Add(10 * time.Millisecond)
	phaseEnd := phaseStart.Add(time.Second)
	jobs := make(chan originalSlot)
	done := make(chan struct{})
	s := &simulator{}
	go func() {
		s.generateOriginalPhase(context.Background(), jobs, phaseStart, phaseEnd, 1, nil)
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(500 * time.Millisecond):
		t.Fatal("generator remained blocked after the only slot expired")
	}
	select {
	case slot := <-jobs:
		t.Fatalf("expired slot remained queued: %#v", slot)
	default:
	}
}
