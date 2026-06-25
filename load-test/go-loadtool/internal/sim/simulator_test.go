package sim

import (
	"context"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestLoadRateUsesHalfTargetDuringWarmup(t *testing.T) {
	targetRate := 2000
	warmup := 30 * time.Second

	if got := loadRateForElapsed(0, warmup, targetRate); got != 1000 {
		t.Fatalf("loadRateForElapsed during warmup = %d, want 1000", got)
	}
	if got := loadRateForElapsed(29*time.Second, warmup, targetRate); got != 1000 {
		t.Fatalf("loadRateForElapsed before warmup end = %d, want 1000", got)
	}
	if got := loadRateForElapsed(30*time.Second, warmup, targetRate); got != 2000 {
		t.Fatalf("loadRateForElapsed after warmup = %d, want 2000", got)
	}
}

func TestLoadRateWarmupNeverDropsBelowOnePerSecond(t *testing.T) {
	if got := loadRateForElapsed(0, 30*time.Second, 1); got != 1 {
		t.Fatalf("loadRateForElapsed with target rate 1 = %d, want 1", got)
	}
}

func TestStatusWorkersProcessQueuedJobsWithBoundedConcurrency(t *testing.T) {
	const workerCount = 4
	const jobCount = 50

	var processed atomic.Int64
	var active atomic.Int64
	var maxActive atomic.Int64
	s := &simulator{
		sendPacs002Func: func(context.Context, string, string) {
			current := active.Add(1)
			for {
				previous := maxActive.Load()
				if current <= previous || maxActive.CompareAndSwap(previous, current) {
					break
				}
			}
			time.Sleep(time.Millisecond)
			active.Add(-1)
			processed.Add(1)
		},
	}

	jobs := make(chan statusJob, jobCount)
	var workers sync.WaitGroup
	s.startStatusWorkers(context.Background(), &workers, jobs, workerCount)
	for i := 0; i < jobCount; i++ {
		jobs <- statusJob{receiverISPB: "20000001", endToEndID: "E2E"}
	}
	close(jobs)
	workers.Wait()

	if got := processed.Load(); got != jobCount {
		t.Fatalf("processed status jobs = %d, want %d", got, jobCount)
	}
	if got := maxActive.Load(); got > workerCount {
		t.Fatalf("max concurrent status workers = %d, want <= %d", got, workerCount)
	}
}
