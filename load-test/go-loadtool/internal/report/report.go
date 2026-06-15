package report

import (
	"encoding/json"
	"io"
	"sort"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/events"
)

type Summary struct {
	Started                           int     `json:"started"`
	Accepted                          int     `json:"accepted"`
	Confirmed                         int     `json:"confirmed"`
	NeverConfirmed                    int     `json:"never_confirmed"`
	MissedSLA                         int     `json:"missed_sla"`
	ActualStartedPerConfiguredSecond  float64 `json:"actual_started_per_configured_second"`
	StartWindowSeconds                float64 `json:"start_window_seconds"`
	ActualStartedPerStartWindowSecond float64 `json:"actual_started_per_start_window_second"`
	StartRateAchievementPct           float64 `json:"start_rate_achievement_pct"`
	P50Ms                             float64 `json:"p50_ms"`
	P95Ms                             float64 `json:"p95_ms"`
	P99Ms                             float64 `json:"p99_ms"`
	MaxMs                             float64 `json:"max_ms"`
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
	summary.Started = len(starts)
	if options.TargetTxRate > 0 && options.Duration > 0 {
		summary.ActualStartedPerConfiguredSecond = float64(summary.Started) / options.Duration.Seconds()
		summary.StartRateAchievementPct = summary.ActualStartedPerConfiguredSecond / float64(options.TargetTxRate) * 100
	}
	if startWindowSeconds := startWindowSeconds(starts); startWindowSeconds > 0 {
		summary.StartWindowSeconds = startWindowSeconds
		summary.ActualStartedPerStartWindowSecond = float64(summary.Started) / startWindowSeconds
	}

	confirmations := payerConfirmations(notifications)
	var durations []float64
	for _, start := range starts {
		if start.HTTPStatus < 200 || start.HTTPStatus >= 300 {
			continue
		}
		summary.Accepted++
		receivedAt, ok := confirmations[confirmationKey{
			endToEndID: start.EndToEndID,
			ispb:       start.PayerISPB,
		}]
		if !ok {
			summary.NeverConfirmed++
			continue
		}
		durationMs := float64(receivedAt-start.CreatedAtNS) / 1_000_000
		durations = append(durations, durationMs)
		summary.Confirmed++
		if durationMs > float64(options.SLAThresholdMs) {
			summary.MissedSLA++
		}
	}

	sort.Float64s(durations)
	summary.P50Ms = percentile(durations, 0.50)
	summary.P95Ms = percentile(durations, 0.95)
	summary.P99Ms = percentile(durations, 0.99)
	if len(durations) > 0 {
		summary.MaxMs = durations[len(durations)-1]
	}
	return summary
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
