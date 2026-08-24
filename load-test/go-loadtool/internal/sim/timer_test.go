package sim

import (
	"testing"
	"time"
)

func TestResetTimerCanBeReusedAfterFiringAndStopping(t *testing.T) {
	timer := time.NewTimer(time.Hour)
	stopTimer(timer)
	defer stopTimer(timer)

	resetTimer(timer, 0)
	assertTimerFires(t, timer)

	resetTimer(timer, time.Hour)
	stopTimer(timer)

	resetTimer(timer, 0)
	assertTimerFires(t, timer)
}

func assertTimerFires(t *testing.T, timer *time.Timer) {
	t.Helper()
	select {
	case <-timer.C:
	case <-time.After(time.Second):
		t.Fatal("timer did not fire")
	}
}
