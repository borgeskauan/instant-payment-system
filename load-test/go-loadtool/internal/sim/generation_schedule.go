package sim

import (
	"math/bits"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/config"
)

const originalBucketDuration = 10 * time.Millisecond

type originalBucket struct {
	start     time.Time
	end       time.Time
	firstSlot uint64
	endSlot   uint64
}

type warmupWindow struct {
	name           string
	start          time.Time
	end            time.Time
	offeredTxRate  int
	requestTimeout time.Duration
}

func warmupWindows(start time.Time, warmup config.Warmup) [2]warmupWindow {
	bootstrapEnd := start.Add(warmup.Bootstrap.Duration)
	return [2]warmupWindow{
		{
			name:           "bootstrap",
			start:          start,
			end:            bootstrapEnd,
			offeredTxRate:  warmup.Bootstrap.OfferedTxRate,
			requestTimeout: warmup.Bootstrap.RequestTimeout,
		},
		{
			name:           "steady",
			start:          bootstrapEnd,
			end:            bootstrapEnd.Add(warmup.Steady.Duration),
			offeredTxRate:  warmup.Steady.OfferedTxRate,
			requestTimeout: warmup.Steady.RequestTimeout,
		},
	}
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

func originalJobCanStart(now, deadline time.Time) bool {
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
