package report

import (
	"sort"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/events"
)

func summarizeGeneration(starts []events.Start, options Options) GenerationSummary {
	const rollingWindow = time.Second
	summary := GenerationSummary{
		OfferedTPS:           options.OfferedTxRate,
		RequiredMinimumTPS:   options.RequiredMinimumTxRate,
		RollingWindowSeconds: 1,
	}
	activeStart := options.Window.ActiveStartedAt.UnixNano()
	generationStart := options.Window.GenerationStartedAt.UnixNano()
	generationEnd := options.Window.GenerationEndedAt.UnixNano()
	timestamps := make([]int64, 0, len(starts))
	for _, start := range starts {
		startedAt := requestStartedAt(start)
		if startedAt < generationStart || startedAt >= generationEnd {
			summary.OutsideWindow++
		}
		if startedAt >= activeStart && startedAt < generationEnd {
			timestamps = append(timestamps, startedAt)
		}
	}
	summary.Started = len(timestamps)
	activeDuration := options.Window.GenerationEndedAt.Sub(options.Window.ActiveStartedAt)
	if activeDuration > 0 {
		summary.AverageTPS = roundMetric(float64(summary.Started) / activeDuration.Seconds())
	}
	if activeDuration < rollingWindow || len(timestamps) == 0 {
		return summary
	}

	sort.Slice(timestamps, func(left, right int) bool { return timestamps[left] < timestamps[right] })
	summary.MinimumObservedTPS = minimumRollingCount(timestamps, activeStart, generationEnd, rollingWindow.Nanoseconds())
	summary.MaximumObservedTPS = maximumRollingCount(timestamps, activeStart, generationEnd, rollingWindow.Nanoseconds())
	summary.SustainedMinimumMet = summary.MinimumObservedTPS >= options.RequiredMinimumTxRate
	return summary
}

func minimumRollingCount(timestamps []int64, activeStart, activeEnd, window int64) int {
	lastStart := activeEnd - window
	left, right := 0, 0
	minimum := len(timestamps)
	lastCandidate := int64(-1)
	observe := func(start int64) {
		for left < len(timestamps) && timestamps[left] < start {
			left++
		}
		if right < left {
			right = left
		}
		end := start + window
		for right < len(timestamps) && timestamps[right] < end {
			right++
		}
		minimum = min(minimum, right-left)
		lastCandidate = start
	}

	observe(activeStart)
	for _, timestamp := range timestamps {
		if timestamp >= lastStart {
			break
		}
		candidate := timestamp + 1
		if candidate != lastCandidate {
			observe(candidate)
		}
	}
	if lastCandidate != lastStart {
		observe(lastStart)
	}
	return minimum
}

func maximumRollingCount(timestamps []int64, activeStart, activeEnd, window int64) int {
	lastStart := activeEnd - window
	left, right := 0, 0
	maximum := 0
	lastCandidate := int64(-1)
	observe := func(start int64) {
		for left < len(timestamps) && timestamps[left] < start {
			left++
		}
		if right < left {
			right = left
		}
		end := start + window
		for right < len(timestamps) && timestamps[right] < end {
			right++
		}
		maximum = max(maximum, right-left)
		lastCandidate = start
	}

	observe(activeStart)
	for _, timestamp := range timestamps {
		if timestamp > lastStart {
			break
		}
		if timestamp != lastCandidate {
			observe(timestamp)
		}
	}
	if lastCandidate != lastStart {
		observe(lastStart)
	}
	return maximum
}
