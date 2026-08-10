package report

import (
	"encoding/json"
	"fmt"
	"io"
	"sort"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/config"
	"instant-payment-system/load-test/go-loadtool/internal/events"
)

type Summary struct {
	Run                        RunSummary         `json:"run"`
	Transactions               TransactionSummary `json:"transactions"`
	ThroughputPerSecond        ThroughputSummary  `json:"throughput_per_second"`
	PayerNotificationLatencyMs LatencySummary     `json:"payer_notification_latency_ms"`
	Scenarios                  []ScenarioSummary  `json:"scenarios"`
	Diagnostics                DiagnosticSummary  `json:"diagnostics"`
}

type ScenarioSummary struct {
	Name                       string                     `json:"name"`
	ConfiguredShare            float64                    `json:"configured_share"`
	Transactions               ScenarioTransactionSummary `json:"transactions"`
	PayerNotificationLatencyMs LatencySummary             `json:"payer_notification_latency_ms"`
}

type ScenarioTransactionSummary struct {
	Started            int                                 `json:"started"`
	Accepted           int                                 `json:"accepted"`
	HTTPStatus         ExpectationMatchSummary             `json:"http_status"`
	PayerNotification  PayerNotificationExpectationSummary `json:"payer_notification"`
	PayerNotifiedBySLA NotifiedBySLASummary                `json:"payer_notified_by_sla"`
	Violations         int                                 `json:"violations"`
}

type ExpectationMatchSummary struct {
	Expectation string `json:"expectation"`
	Matched     int    `json:"matched"`
	Violations  int    `json:"violations"`
}

type PayerNotificationExpectationSummary struct {
	DeliverySemantics   string   `json:"delivery_semantics"`
	ExpectedStatus      string   `json:"expected_status"`
	ExpectedReasonCodes []string `json:"expected_reason_codes"`
	Eligible            int      `json:"eligible"`
	Observed            int      `json:"observed"`
	Matched             int      `json:"matched"`
	Missing             int      `json:"missing"`
	StatusMismatch      int      `json:"status_mismatch"`
	ReasonCodesMismatch int      `json:"reason_codes_mismatch"`
	Violations          int      `json:"violations"`
}

type RunSummary struct {
	TargetTPS      int     `json:"target_tps"`
	WarmupSeconds  float64 `json:"warmup_seconds"`
	ActiveSeconds  float64 `json:"active_seconds"`
	SLAThresholdMs int64   `json:"sla_threshold_ms"`
}

type TransactionSummary struct {
	Started            int                      `json:"started"`
	Accepted           int                      `json:"accepted"`
	PayerNotification  PayerNotificationSummary `json:"payer_notification"`
	PayerNotifiedBySLA NotifiedBySLASummary     `json:"payer_notified_by_sla"`
}

type PayerNotificationSummary struct {
	Notified    int `json:"notified"`
	NotNotified int `json:"not_notified"`
}

type NotifiedBySLASummary struct {
	WithinSLA int `json:"within_sla"`
	AfterSLA  int `json:"after_sla"`
}

type ThroughputSummary struct {
	Started                   float64 `json:"started"`
	PayerNotifiedDuringActive float64 `json:"payer_notified_during_active"`
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
	PayerNotifiedAfterActive int     `json:"payer_notified_after_active"`
	PayerNotifiedTotal       int     `json:"payer_notified_total"`
	PayerNotifiedTotalRate   float64 `json:"payer_notified_total_per_second"`
}

type Options struct {
	SLAThresholdMs int64
	TargetTxRate   int
	Warmup         time.Duration
	Duration       time.Duration
	Scenarios      []config.Scenario
}

func BuildWithOptions(starts []events.Start, notifications []events.Notification, options Options) (Summary, error) {
	var summary Summary
	scenarios := options.Scenarios
	if len(scenarios) == 0 {
		return Summary{}, fmt.Errorf("report requires at least one configured scenario")
	}
	if err := validateStartScenarios(starts, scenarios); err != nil {
		return Summary{}, err
	}
	summary.Run.TargetTPS = options.TargetTxRate
	summary.Run.SLAThresholdMs = options.SLAThresholdMs
	if options.Warmup > 0 {
		summary.Run.WarmupSeconds = options.Warmup.Seconds()
	}
	if options.Duration > 0 {
		summary.Run.ActiveSeconds = options.Duration.Seconds()
	}
	summary.Scenarios = make([]ScenarioSummary, len(scenarios))
	scenarioIndexes := make(map[string]int, len(scenarios))
	scenarioDurations := make([][]float64, len(scenarios))
	for index, scenario := range scenarios {
		if scenario.Expectations.HTTPStatus != config.ExpectedHTTP2xx {
			return Summary{}, fmt.Errorf("unsupported HTTP expectation %q for scenario %q", scenario.Expectations.HTTPStatus, scenario.Name)
		}
		if scenario.Expectations.PayerNotification.DeliverySemantics != config.DeliveryAtLeastOnce {
			return Summary{}, fmt.Errorf("unsupported payer notification delivery semantics %q for scenario %q", scenario.Expectations.PayerNotification.DeliverySemantics, scenario.Name)
		}
		scenarioIndexes[scenario.Name] = index
		summary.Scenarios[index] = ScenarioSummary{
			Name:            scenario.Name,
			ConfiguredShare: scenario.Share,
			Transactions: ScenarioTransactionSummary{
				HTTPStatus: ExpectationMatchSummary{Expectation: scenario.Expectations.HTTPStatus},
				PayerNotification: PayerNotificationExpectationSummary{
					DeliverySemantics:   scenario.Expectations.PayerNotification.DeliverySemantics,
					ExpectedStatus:      scenario.Expectations.PayerNotification.Status,
					ExpectedReasonCodes: cloneStrings(scenario.Expectations.PayerNotification.ReasonCodes),
				},
			},
		}
	}
	measuredStarts := measuredWindowStarts(starts, options.Warmup, options.Duration)
	summary.Transactions.Started = len(starts)
	if options.Duration > 0 {
		summary.ThroughputPerSecond.Started = float64(len(measuredStarts)) / options.Duration.Seconds()
	}

	payerNotifications := collectPayerNotifications(notifications)
	for _, start := range starts {
		scenario, err := scenarioForStart(start, scenarios)
		if err != nil {
			return Summary{}, err
		}
		scenarioSummary := &summary.Scenarios[scenarioIndexes[scenario.Name]]
		scenarioSummary.Transactions.Started++
		if start.HTTPStatus < 200 || start.HTTPStatus >= 300 {
			scenarioSummary.Transactions.HTTPStatus.Violations++
			scenarioSummary.Transactions.Violations++
			continue
		}
		summary.Transactions.Accepted++
		scenarioSummary.Transactions.Accepted++
		scenarioSummary.Transactions.HTTPStatus.Matched++
		scenarioSummary.Transactions.PayerNotification.Eligible++
		observation := payerNotifications[notificationKey{
			endToEndID: start.EndToEndID,
			ispb:       start.PayerISPB,
		}]
		if len(observation.deliveries) == 0 {
			scenarioSummary.Transactions.PayerNotification.Missing++
			scenarioSummary.Transactions.PayerNotification.Violations++
			scenarioSummary.Transactions.Violations++
			summary.Transactions.PayerNotification.NotNotified++
			continue
		}
		scenarioSummary.Transactions.PayerNotification.Observed++
		summary.Transactions.PayerNotification.Notified++
		match := matchPayerNotification(observation, scenario.Expectations.PayerNotification)
		if match.statusMismatch {
			scenarioSummary.Transactions.PayerNotification.StatusMismatch++
		}
		if match.reasonCodesMismatch {
			scenarioSummary.Transactions.PayerNotification.ReasonCodesMismatch++
		}
		if match.matched {
			scenarioSummary.Transactions.PayerNotification.Matched++
		}
		if match.statusMismatch || match.reasonCodesMismatch {
			scenarioSummary.Transactions.PayerNotification.Violations++
			scenarioSummary.Transactions.Violations++
		}
	}

	activeWindowEndNS := configuredActiveWindowEndNS(starts, options.Warmup, options.Duration)
	payerNotifiedDuringActive := 0
	matchedActivePayments := 0
	var durations []float64
	for _, start := range measuredStarts {
		if start.HTTPStatus < 200 || start.HTTPStatus >= 300 {
			continue
		}
		scenario, err := scenarioForStart(start, scenarios)
		if err != nil {
			return Summary{}, err
		}
		observation := payerNotifications[notificationKey{endToEndID: start.EndToEndID, ispb: start.PayerISPB}]
		match := matchPayerNotification(observation, scenario.Expectations.PayerNotification)
		if !match.matched {
			continue
		}
		matchedActivePayments++
		durationMs := float64(match.earliestMatchingAt-requestStartedAt(start)) / 1_000_000
		durations = append(durations, durationMs)
		scenarioIndex := scenarioIndexes[scenario.Name]
		scenarioDurations[scenarioIndex] = append(scenarioDurations[scenarioIndex], durationMs)
		if activeWindowEndNS > 0 && match.earliestMatchingAt <= activeWindowEndNS {
			payerNotifiedDuringActive++
		}
		scenarioSummary := &summary.Scenarios[scenarioIndex]
		if durationMs > float64(options.SLAThresholdMs) {
			summary.Transactions.PayerNotifiedBySLA.AfterSLA++
			scenarioSummary.Transactions.PayerNotifiedBySLA.AfterSLA++
		} else {
			summary.Transactions.PayerNotifiedBySLA.WithinSLA++
			scenarioSummary.Transactions.PayerNotifiedBySLA.WithinSLA++
		}
	}
	if options.Duration > 0 {
		durationSeconds := options.Duration.Seconds()
		summary.ThroughputPerSecond.PayerNotifiedDuringActive = float64(payerNotifiedDuringActive) / durationSeconds
		summary.Diagnostics.ResultCollection.PayerNotifiedTotalRate = float64(matchedActivePayments) / durationSeconds
	}
	summary.Diagnostics.ResultCollection.PayerNotifiedAfterActive = matchedActivePayments - payerNotifiedDuringActive
	summary.Diagnostics.ResultCollection.PayerNotifiedTotal = matchedActivePayments

	sort.Float64s(durations)
	summary.PayerNotificationLatencyMs.P50 = percentile(durations, 0.50)
	summary.PayerNotificationLatencyMs.P95 = percentile(durations, 0.95)
	summary.PayerNotificationLatencyMs.P99 = percentile(durations, 0.99)
	if len(durations) > 0 {
		summary.PayerNotificationLatencyMs.Max = durations[len(durations)-1]
	}
	for index := range summary.Scenarios {
		sort.Float64s(scenarioDurations[index])
		summary.Scenarios[index].PayerNotificationLatencyMs.P50 = percentile(scenarioDurations[index], 0.50)
		summary.Scenarios[index].PayerNotificationLatencyMs.P95 = percentile(scenarioDurations[index], 0.95)
		summary.Scenarios[index].PayerNotificationLatencyMs.P99 = percentile(scenarioDurations[index], 0.99)
		if len(scenarioDurations[index]) > 0 {
			summary.Scenarios[index].PayerNotificationLatencyMs.Max = scenarioDurations[index][len(scenarioDurations[index])-1]
		}
	}
	return summary, nil
}

func validateStartScenarios(starts []events.Start, scenarios []config.Scenario) error {
	for _, start := range starts {
		if _, err := scenarioForStart(start, scenarios); err != nil {
			return err
		}
	}
	return nil
}

func scenarioForStart(start events.Start, scenarios []config.Scenario) (config.Scenario, error) {
	for _, scenario := range scenarios {
		if scenario.Name != start.ScenarioName {
			continue
		}
		return scenario, nil
	}
	return config.Scenario{}, fmt.Errorf("start %q uses unknown scenario name %q", start.EndToEndID, start.ScenarioName)
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

type notificationKey struct {
	endToEndID string
	ispb       string
}

type notificationObservation struct {
	deliveries []events.Notification
}

func collectPayerNotifications(notifications []events.Notification) map[notificationKey]notificationObservation {
	observations := make(map[notificationKey]notificationObservation)
	for _, notification := range notifications {
		if notification.EventType != events.EventPacs002Received {
			continue
		}
		key := notificationKey{
			endToEndID: notification.EndToEndID,
			ispb:       notification.ISPB,
		}
		observation := observations[key]
		observation.deliveries = append(observation.deliveries, notification)
		observations[key] = observation
	}
	return observations
}

type payerNotificationMatch struct {
	matched             bool
	statusMismatch      bool
	reasonCodesMismatch bool
	earliestMatchingAt  int64
}

func matchPayerNotification(observation notificationObservation, expectation config.PayerNotificationExpectation) payerNotificationMatch {
	if len(observation.deliveries) == 0 {
		return payerNotificationMatch{}
	}
	result := payerNotificationMatch{}
	for _, delivery := range observation.deliveries {
		statusMatches := delivery.StatusCode == expectation.Status
		reasonsMatch := equalReasonCodes(delivery.ReasonCodes, expectation.ReasonCodes)
		if !statusMatches {
			result.statusMismatch = true
		}
		if !reasonsMatch {
			result.reasonCodesMismatch = true
		}
		if statusMatches && reasonsMatch {
			if !result.matched || delivery.ReceivedAtNS < result.earliestMatchingAt {
				result.earliestMatchingAt = delivery.ReceivedAtNS
			}
			result.matched = true
		}
	}
	return result
}

func equalReasonCodes(left, right []string) bool {
	if len(left) != len(right) {
		return false
	}
	leftCopy := cloneStrings(left)
	rightCopy := cloneStrings(right)
	sort.Strings(leftCopy)
	sort.Strings(rightCopy)
	for index := range leftCopy {
		if leftCopy[index] != rightCopy[index] {
			return false
		}
	}
	return true
}

func cloneStrings(values []string) []string {
	cloned := make([]string, len(values))
	copy(cloned, values)
	return cloned
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

	summary, err := BuildWithOptions(starts, notifications, options)
	if err != nil {
		return err
	}
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
