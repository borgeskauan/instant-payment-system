package report

import (
	"encoding/json"
	"io"
	"sort"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/events"
)

type Summary struct {
	Run                 RunSummary         `json:"run"`
	Transactions        TransactionSummary `json:"transactions"`
	ThroughputPerSecond ThroughputSummary  `json:"throughput_per_second"`
	LatencyMs           LatencySummary     `json:"latency_ms"`
	Diagnostics         DiagnosticSummary  `json:"diagnostics"`
}

type RunSummary struct {
	TargetTPS      int     `json:"target_tps"`
	WarmupSeconds  float64 `json:"warmup_seconds"`
	ActiveSeconds  float64 `json:"active_seconds"`
	SLAThresholdMs int64   `json:"sla_threshold_ms"`
}

type TransactionSummary struct {
	Started        int                   `json:"started"`
	Accepted       int                   `json:"accepted"`
	Confirmation   ConfirmationSummary   `json:"confirmation"`
	ConfirmedBySLA ConfirmedBySLASummary `json:"confirmed_by_sla"`
}

type ConfirmationSummary struct {
	Confirmed    int `json:"confirmed"`
	NotConfirmed int `json:"not_confirmed"`
}

type ConfirmedBySLASummary struct {
	WithinSLA int `json:"within_sla"`
	AfterSLA  int `json:"after_sla"`
}

type ThroughputSummary struct {
	Started               float64 `json:"started"`
	ConfirmedDuringActive float64 `json:"confirmed_during_active"`
}

type LatencySummary struct {
	P50 float64 `json:"p50"`
	P95 float64 `json:"p95"`
	P99 float64 `json:"p99"`
	Max float64 `json:"max"`
}

type DiagnosticSummary struct {
	ResultCollection ResultCollectionSummary `json:"result_collection"`
}

type ResultCollectionSummary struct {
	ConfirmedAfterActive int     `json:"confirmed_after_active"`
	ConfirmedTotal       int     `json:"confirmed_total"`
	ConfirmedTotalRate   float64 `json:"confirmed_total_per_second"`
}

type Options struct {
	SLAThresholdMs int64
	TargetTxRate   int
	Warmup         time.Duration
	Duration       time.Duration
}

func BuildWithOptions(starts []events.Start, notifications []events.Notification, options Options) Summary {
	var summary Summary
	summary.Run.TargetTPS = options.TargetTxRate
	summary.Run.SLAThresholdMs = options.SLAThresholdMs
	if options.Warmup > 0 {
		summary.Run.WarmupSeconds = options.Warmup.Seconds()
	}
	if options.Duration > 0 {
		summary.Run.ActiveSeconds = options.Duration.Seconds()
	}
	measuredStarts := measuredWindowStarts(starts, options.Warmup, options.Duration)
	summary.Transactions.Started = len(measuredStarts)
	if options.Duration > 0 {
		summary.ThroughputPerSecond.Started = float64(summary.Transactions.Started) / options.Duration.Seconds()
	}

	confirmations := payerConfirmations(notifications)
	activeWindowEndNS := configuredActiveWindowEndNS(starts, options.Warmup, options.Duration)
	confirmedDuringActive := 0
	var durations []float64
	for _, start := range measuredStarts {
		if start.HTTPStatus < 200 || start.HTTPStatus >= 300 {
			continue
		}
		summary.Transactions.Accepted++
		receivedAt, ok := confirmations[confirmationKey{
			endToEndID: start.EndToEndID,
			ispb:       start.PayerISPB,
		}]
		if !ok {
			summary.Transactions.Confirmation.NotConfirmed++
			continue
		}
		durationMs := float64(receivedAt-requestStartedAt(start)) / 1_000_000
		durations = append(durations, durationMs)
		summary.Transactions.Confirmation.Confirmed++
		if activeWindowEndNS > 0 && receivedAt <= activeWindowEndNS {
			confirmedDuringActive++
		}
		if durationMs > float64(options.SLAThresholdMs) {
			summary.Transactions.ConfirmedBySLA.AfterSLA++
		} else {
			summary.Transactions.ConfirmedBySLA.WithinSLA++
		}
	}
	if options.Duration > 0 {
		durationSeconds := options.Duration.Seconds()
		summary.ThroughputPerSecond.ConfirmedDuringActive = float64(confirmedDuringActive) / durationSeconds
		summary.Diagnostics.ResultCollection.ConfirmedTotalRate = float64(summary.Transactions.Confirmation.Confirmed) / durationSeconds
	}
	summary.Diagnostics.ResultCollection.ConfirmedAfterActive = summary.Transactions.Confirmation.Confirmed - confirmedDuringActive
	summary.Diagnostics.ResultCollection.ConfirmedTotal = summary.Transactions.Confirmation.Confirmed

	sort.Float64s(durations)
	summary.LatencyMs.P50 = percentile(durations, 0.50)
	summary.LatencyMs.P95 = percentile(durations, 0.95)
	summary.LatencyMs.P99 = percentile(durations, 0.99)
	if len(durations) > 0 {
		summary.LatencyMs.Max = durations[len(durations)-1]
	}
	return summary
}

func configuredActiveWindowEndNS(starts []events.Start, warmup time.Duration, duration time.Duration) int64 {
	if len(starts) == 0 || duration <= 0 {
		return 0
	}
	return firstStartedAt(starts) + warmup.Nanoseconds() + duration.Nanoseconds()
}

func measuredWindowStarts(starts []events.Start, warmup time.Duration, duration time.Duration) []events.Start {
	if len(starts) == 0 {
		return nil
	}
	windowStart := firstStartedAt(starts) + warmup.Nanoseconds()
	windowEnd := int64(0)
	if duration > 0 {
		windowEnd = windowStart + duration.Nanoseconds()
	}
	measured := make([]events.Start, 0, len(starts))
	for _, start := range starts {
		if requestStartedAt(start) < windowStart {
			continue
		}
		if windowEnd > 0 && requestStartedAt(start) >= windowEnd {
			continue
		}
		measured = append(measured, start)
	}
	return measured
}

func firstStartedAt(starts []events.Start) int64 {
	minStartedAt := requestStartedAt(starts[0])
	for _, start := range starts[1:] {
		startedAt := requestStartedAt(start)
		if startedAt < minStartedAt {
			minStartedAt = startedAt
		}
	}
	return minStartedAt
}

func requestStartedAt(start events.Start) int64 {
	if start.RequestStartedAtNS != 0 {
		return start.RequestStartedAtNS
	}
	return start.CreatedAtNS
}

type confirmationKey struct {
	endToEndID string
	ispb       string
}

func payerConfirmations(notifications []events.Notification) map[confirmationKey]int64 {
	confirmations := make(map[confirmationKey]int64)
	for _, notification := range notifications {
		if notification.EventType != events.EventPacs002Received {
			continue
		}
		key := confirmationKey{
			endToEndID: notification.EndToEndID,
			ispb:       notification.ISPB,
		}
		if receivedAt, ok := confirmations[key]; !ok || notification.ReceivedAtNS < receivedAt {
			confirmations[key] = notification.ReceivedAtNS
		}
	}
	return confirmations
}

func Print(startsPath string, eventsPath string, options Options, output io.Writer) error {
	starts, err := events.ReadStarts(startsPath)
	if err != nil {
		return err
	}
	notifications, err := events.ReadNotifications(eventsPath)
	if err != nil {
		return err
	}

	summary := BuildWithOptions(starts, notifications, options)
	encoder := json.NewEncoder(output)
	encoder.SetIndent("", "  ")
	return encoder.Encode(summary)
}

func percentile(values []float64, quantile float64) float64 {
	if len(values) == 0 {
		return 0
	}
	if len(values) == 1 {
		return values[0]
	}
	index := quantile * float64(len(values)-1)
	lower := int(index)
	upper := lower + 1
	if upper >= len(values) {
		return values[len(values)-1]
	}
	weight := index - float64(lower)
	return values[lower]*(1-weight) + values[upper]*weight
}
