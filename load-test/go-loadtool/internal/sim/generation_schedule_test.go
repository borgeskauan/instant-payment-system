package sim

import (
	"context"
	"testing"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/config"
)

func TestWarmupWindowsKeepBootstrapAndSteadyStagesContiguous(t *testing.T) {
	start := time.Unix(100, 0)
	warmup := config.Warmup{
		Bootstrap:         config.WarmupStage{OfferedTxRate: 500, Duration: time.Minute, RequestTimeout: 30 * time.Second},
		Steady:            config.WarmupStage{OfferedTxRate: 1_500, Duration: time.Minute, RequestTimeout: 5 * time.Second},
		CompletionTimeout: 2 * time.Minute,
	}

	windows := warmupWindows(start, warmup)

	if windows[0].name != "bootstrap" || windows[0].offeredTxRate != 500 || windows[0].requestTimeout != 30*time.Second || !windows[0].start.Equal(start) || !windows[0].end.Equal(start.Add(time.Minute)) {
		t.Fatalf("bootstrap window = %#v", windows[0])
	}
	if windows[1].name != "steady" || windows[1].offeredTxRate != 1_500 || windows[1].requestTimeout != 5*time.Second || !windows[1].start.Equal(windows[0].end) || !windows[1].end.Equal(start.Add(2*time.Minute)) {
		t.Fatalf("steady window = %#v", windows[1])
	}
}

func TestOriginalBucketCarriesExactBudgetForConfiguredRates(t *testing.T) {
	start := time.Unix(100, 0)
	end := start.Add(time.Second)

	active, ok := originalBucketAt(start, end, 2_100, 0)
	if !ok {
		t.Fatal("active bucket 0 is absent")
	}
	if active.firstSlot != 0 || active.endSlot != 21 {
		t.Fatalf("active bucket slots = [%d,%d), want [0,21)", active.firstSlot, active.endSlot)
	}
	if !active.start.Equal(start) || !active.end.Equal(start.Add(10*time.Millisecond)) {
		t.Fatalf("active bucket bounds = [%s,%s), want [%s,%s)", active.start, active.end, start, start.Add(10*time.Millisecond))
	}

	warmup, ok := originalBucketAt(start, end, 1_500, 0)
	if !ok {
		t.Fatal("warmup bucket 0 is absent")
	}
	if warmup.firstSlot != 0 || warmup.endSlot != 15 {
		t.Fatalf("warmup bucket slots = [%d,%d), want [0,15)", warmup.firstSlot, warmup.endSlot)
	}
}

func TestOriginalBucketsDistributeNonDivisibleRateWithoutChangingTotal(t *testing.T) {
	start := time.Unix(100, 0)
	end := start.Add(time.Second)

	first, ok := originalBucketAt(start, end, 2_050, 0)
	if !ok {
		t.Fatal("bucket 0 is absent")
	}
	second, ok := originalBucketAt(start, end, 2_050, 1)
	if !ok {
		t.Fatal("bucket 1 is absent")
	}
	if first.endSlot-first.firstSlot != 21 || second.endSlot-second.firstSlot != 20 {
		t.Fatalf("first bucket budgets = %d,%d, want 21,20", first.endSlot-first.firstSlot, second.endSlot-second.firstSlot)
	}

	var total uint64
	for index := uint64(0); ; index++ {
		bucket, exists := originalBucketAt(start, end, 2_050, index)
		if !exists {
			break
		}
		total += bucket.endSlot - bucket.firstSlot
	}
	if total != 2_050 {
		t.Fatalf("bucket total = %d, want 2050", total)
	}
}

func TestCurrentOriginalBucketJumpsDirectlyOverExpiredBuckets(t *testing.T) {
	start := time.Unix(100, 0)
	now := start.Add(49*time.Second + 123*time.Millisecond)

	index := currentOriginalBucketIndex(start, now)

	if index != 4_912 {
		t.Fatalf("current bucket = %d, want 4912", index)
	}
	bucket, ok := originalBucketAt(start, start.Add(time.Minute), 2_100, index)
	if !ok {
		t.Fatalf("current bucket %d is absent", index)
	}
	if now.Before(bucket.start) || !now.Before(bucket.end) {
		t.Fatalf("now %s is outside current bucket [%s,%s)", now, bucket.start, bucket.end)
	}
}

func TestOriginalBucketUsesFixedPhaseBoundariesAndExclusiveDeadline(t *testing.T) {
	start := time.Unix(100, 0)
	phaseEnd := start.Add(25 * time.Millisecond)

	bucket, ok := originalBucketAt(start, phaseEnd, 100, 2)
	if !ok {
		t.Fatal("final partial bucket is absent")
	}
	if !bucket.start.Equal(start.Add(20*time.Millisecond)) || !bucket.end.Equal(phaseEnd) {
		t.Fatalf("final bucket bounds = [%s,%s), want [%s,%s)", bucket.start, bucket.end, start.Add(20*time.Millisecond), phaseEnd)
	}
	if bucket.firstSlot != 2 || bucket.endSlot != 3 {
		t.Fatalf("final bucket slots = [%d,%d), want [2,3)", bucket.firstSlot, bucket.endSlot)
	}
	if originalJobCanStart(bucket.end, bucket.end) {
		t.Fatal("slot was allowed to start at the exclusive bucket deadline")
	}
	if _, exists := originalBucketAt(start, phaseEnd, 100, 3); exists {
		t.Fatal("bucket was created beyond the phase boundary")
	}
}

func TestOriginalPhaseHasExactConfiguredSlotCount(t *testing.T) {
	if got := originalPhaseSlotCount(3, 2*time.Second); got != 6 {
		t.Fatalf("phase slots = %d, want 6", got)
	}
	if got := originalPhaseSlotCount(2_000, time.Minute); got != 120_000 {
		t.Fatalf("phase slots = %d, want 120000", got)
	}
	if got := originalPhaseSlotCount(100, 25*time.Millisecond); got != 3 {
		t.Fatalf("partial phase slots = %d, want 3", got)
	}
}

func TestOriginalPhaseDoesNotQueueSlotPastItsDeadline(t *testing.T) {
	phaseStart := time.Now().Add(10 * time.Millisecond)
	phaseEnd := phaseStart.Add(time.Second)
	planner, err := newWorkloadPlanner(mixedPlannerScenarios())
	if err != nil {
		t.Fatal(err)
	}
	jobs := make(chan transferJob)
	done := make(chan struct{})
	s := &simulator{runID: "expired-generation", originalPlanner: planner}
	go func() {
		s.generateOriginalPhase(context.Background(), jobs, phaseStart, phaseEnd, 1, defaultRequestTimeout, nil)
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
