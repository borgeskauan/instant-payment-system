package report

import (
	"encoding/json"
	"io"
	"sort"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/events"
)

type Summary struct {
	Transactions        TransactionSummary `json:"transactions"`
	ThroughputPerSecond ThroughputSummary  `json:"throughput_per_second"`
	Windows             WindowSummary      `json:"windows"`
	SLA                 SLASummary         `json:"sla"`
	LatencyMs           LatencySummary     `json:"latency_ms"`
}

type TransactionSummary struct {
	Started        int                   `json:"started"`
	Accepted       int                   `json:"accepted"`
	Completion     CompletionSummary     `json:"completion"`
	CompletedBySLA CompletedBySLASummary `json:"completed_by_sla"`
}

type CompletionSummary struct {
	Completed    int `json:"completed"`
	NotCompleted int `json:"not_completed"`
}

type CompletedBySLASummary struct {
	WithinSLA int `json:"within_sla"`
	AfterSLA  int `json:"after_sla"`
}

type ThroughputSummary struct {
	Started                 float64 `json:"started"`
	StartedActualWindow     float64 `json:"started_actual_window"`
	CompletedDuringActive   float64 `json:"completed_during_active"`
	CompletedIncludingDrain float64 `json:"completed_including_drain"`
}

type WindowSummary struct {
	ConfiguredActiveSeconds  float64 `json:"configured_active_seconds"`
	ActualStartWindowSeconds float64 `json:"actual_start_window_seconds"`
}

type SLASummary struct {
	ThresholdMs int64 `json:"threshold_ms"`
}

type LatencySummary struct {
	P50 float64 `json:"p50"`
	P95 float64 `json:"p95"`
	P99 float64 `json:"p99"`
	Max float64 `json:"max"`
}

type Options struct {
	SLAThresholdMs int64
	TargetTxRate   int
	Duration       time.Duration
}

func Build(starts []events.Start, notifications []events.Notification, slaThresholdMs int64) Summary {
	return BuildWithOptions(starts, notifications, Options{SLAThresholdMs: slaThresholdMs})
}

func BuildWithOptions(starts []events.Start, notifications []events.Notification, options Options) Summary {
	var summary Summary
	summary.Transactions.Started = len(starts)
	summary.SLA.ThresholdMs = options.SLAThresholdMs
	if options.Duration > 0 {
		summary.Windows.ConfiguredActiveSeconds = options.Duration.Seconds()
		summary.ThroughputPerSecond.Started = float64(summary.Transactions.Started) / options.Duration.Seconds()
	}
	if startWindowSeconds := startWindowSeconds(starts); startWindowSeconds > 0 {
		summary.Windows.ActualStartWindowSeconds = startWindowSeconds
		summary.ThroughputPerSecond.StartedActualWindow = float64(summary.Transactions.Started) / startWindowSeconds
	}

	confirmations := payerConfirmations(notifications)
	activeWindowEndNS := configuredActiveWindowEndNS(starts, options.Duration)
	completedDuringActive := 0
	var durations []float64
	for _, start := range starts {
		if start.HTTPStatus < 200 || start.HTTPStatus >= 300 {
			continue
		}
		summary.Transactions.Accepted++
		receivedAt, ok := confirmations[confirmationKey{
			endToEndID: start.EndToEndID,
			ispb:       start.PayerISPB,
		}]
		if !ok {
			summary.Transactions.Completion.NotCompleted++
			continue
		}
		durationMs := float64(receivedAt-start.CreatedAtNS) / 1_000_000
		durations = append(durations, durationMs)
		summary.Transactions.Completion.Completed++
		if activeWindowEndNS > 0 && receivedAt <= activeWindowEndNS {
			completedDuringActive++
		}
		if durationMs > float64(options.SLAThresholdMs) {
			summary.Transactions.CompletedBySLA.AfterSLA++
		} else {
			summary.Transactions.CompletedBySLA.WithinSLA++
		}
	}
	if options.Duration > 0 {
		durationSeconds := options.Duration.Seconds()
		summary.ThroughputPerSecond.CompletedDuringActive = float64(completedDuringActive) / durationSeconds
		summary.ThroughputPerSecond.CompletedIncludingDrain = float64(summary.Transactions.Completion.Completed) / durationSeconds
	}

	sort.Float64s(durations)
	summary.LatencyMs.P50 = percentile(durations, 0.50)
	summary.LatencyMs.P95 = percentile(durations, 0.95)
	summary.LatencyMs.P99 = percentile(durations, 0.99)
	if len(durations) > 0 {
		summary.LatencyMs.Max = durations[len(durations)-1]
	}
	return summary
}

func configuredActiveWindowEndNS(starts []events.Start, duration time.Duration) int64 {
	if len(starts) == 0 || duration <= 0 {
		return 0
	}
	minStartedAt := starts[0].CreatedAtNS
	for _, start := range starts[1:] {
		if start.CreatedAtNS < minStartedAt {
			minStartedAt = start.CreatedAtNS
		}
	}
	return minStartedAt + duration.Nanoseconds()
}

func startWindowSeconds(starts []events.Start) float64 {
	if len(starts) < 2 {
		return 0
	}
	minStartedAt := starts[0].CreatedAtNS
	maxStartedAt := starts[0].CreatedAtNS
	for _, start := range starts[1:] {
		if start.CreatedAtNS < minStartedAt {
			minStartedAt = start.CreatedAtNS
		}
		if start.CreatedAtNS > maxStartedAt {
			maxStartedAt = start.CreatedAtNS
		}
	}
	return float64(maxStartedAt-minStartedAt) / 1_000_000_000
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
