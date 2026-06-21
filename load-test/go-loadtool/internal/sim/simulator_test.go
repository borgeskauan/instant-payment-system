package sim

import (
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
