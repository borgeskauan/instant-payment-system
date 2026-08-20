package sim

import (
	"math/bits"
	"time"
)

const originalStartTolerance = 10 * time.Millisecond

type originalSlot struct {
	createdAt int64
	deadline  time.Time
}

func originalSlotScheduledAt(phaseStart time.Time, rate int, index uint64) time.Time {
	unsignedRate := uint64(rate)
	wholeSeconds := index / unsignedRate
	remainder := index % unsignedRate
	remainderNanoseconds := multiplyDivideFloor(remainder, uint64(time.Second), unsignedRate)
	return phaseStart.Add(time.Duration(wholeSeconds)*time.Second + time.Duration(remainderNanoseconds))
}

func firstUnexpiredOriginalSlot(phaseStart time.Time, rate int, now time.Time, tolerance time.Duration) uint64 {
	threshold := now.Add(-tolerance)
	if threshold.Before(phaseStart) {
		return 0
	}
	elapsedThroughThreshold := uint64(threshold.Sub(phaseStart).Nanoseconds()) + 1
	return multiplyDivideCeil(elapsedThroughThreshold, uint64(rate), uint64(time.Second))
}

func originalSlotDeadline(scheduledAt, phaseEnd time.Time, tolerance time.Duration) time.Time {
	deadline := scheduledAt.Add(tolerance)
	if phaseEnd.Before(deadline) {
		return phaseEnd
	}
	return deadline
}

func originalSlotCanStart(now, deadline time.Time) bool {
	return now.Before(deadline)
}

func originalPhaseSlotCount(rate int, duration time.Duration) uint64 {
	return uint64(rate) * uint64(duration/time.Second)
}

func multiplyDivideFloor(left, right, divisor uint64) uint64 {
	high, low := bits.Mul64(left, right)
	quotient, _ := bits.Div64(high, low, divisor)
	return quotient
}

func multiplyDivideCeil(left, right, divisor uint64) uint64 {
	high, low := bits.Mul64(left, right)
	quotient, remainder := bits.Div64(high, low, divisor)
	if remainder != 0 {
		quotient++
	}
	return quotient
}
