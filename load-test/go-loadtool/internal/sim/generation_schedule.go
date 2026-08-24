package sim

import (
	"math/bits"
	"time"
)

const originalBucketDuration = 10 * time.Millisecond

type originalBucket struct {
	start     time.Time
	end       time.Time
	firstSlot uint64
	endSlot   uint64
}

type originalSlot struct {
	createdAt int64
	deadline  time.Time
	tracker   *phaseTracker
}

func originalBucketAt(phaseStart, phaseEnd time.Time, rate int, index uint64) (originalBucket, bool) {
	if rate <= 0 || !phaseStart.Before(phaseEnd) {
		return originalBucket{}, false
	}
	startOffset := time.Duration(index) * originalBucketDuration
	if startOffset < 0 {
		return originalBucket{}, false
	}
	start := phaseStart.Add(startOffset)
	if !start.Before(phaseEnd) {
		return originalBucket{}, false
	}
	end := start.Add(originalBucketDuration)
	if phaseEnd.Before(end) {
		end = phaseEnd
	}
	return originalBucket{
		start:     start,
		end:       end,
		firstSlot: slotsThrough(phaseStart, start, rate),
		endSlot:   slotsThrough(phaseStart, end, rate),
	}, true
}

func currentOriginalBucketIndex(phaseStart, now time.Time) uint64 {
	if !phaseStart.Before(now) {
		return 0
	}
	return uint64(now.Sub(phaseStart) / originalBucketDuration)
}

func originalSlotCanStart(now, deadline time.Time) bool {
	return now.Before(deadline)
}

func originalPhaseSlotCount(rate int, duration time.Duration) uint64 {
	if rate <= 0 || duration <= 0 {
		return 0
	}
	return multiplyDivideCeil(uint64(duration), uint64(rate), uint64(time.Second))
}

func slotsThrough(phaseStart, boundary time.Time, rate int) uint64 {
	if !phaseStart.Before(boundary) {
		return 0
	}
	return multiplyDivideCeil(uint64(boundary.Sub(phaseStart)), uint64(rate), uint64(time.Second))
}

func multiplyDivideCeil(left, right, divisor uint64) uint64 {
	high, low := bits.Mul64(left, right)
	quotient, remainder := bits.Div64(high, low, divisor)
	if remainder != 0 {
		quotient++
	}
	return quotient
}
