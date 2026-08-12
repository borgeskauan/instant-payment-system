package report

import (
	"encoding/json"
	"fmt"
	"io"
	"math"
	"sort"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/config"
	"instant-payment-system/load-test/go-loadtool/internal/events"
	"instant-payment-system/load-test/go-loadtool/internal/runwindow"
)

type Summary struct {
	Valid       bool               `json:"valid"`
	Generation  GenerationSummary  `json:"generation"`
	Scenarios   []ScenarioSummary  `json:"scenarios"`
	Replays     ReplaySummary      `json:"replays"`
	Performance PerformanceSummary `json:"performance"`
}

type ReplaySummary struct {
	Pacs008 ReplayTypeSummary `json:"pacs008"`
	Pacs002 ReplayTypeSummary `json:"pacs002"`
}

type GenerationSummary struct {
	TargetTPS  int     `json:"target_tps"`
	Expected   int     `json:"expected"`
	Started    int     `json:"started"`
	ActualTPS  float64 `json:"actual_tps"`
	Violations int     `json:"violations"`
}

type ReplayTypeSummary struct {
	Started    int `json:"started"`
	Accepted   int `json:"accepted"`
	Violations int `json:"violations"`
}

type ScenarioSummary struct {
	Name        string                     `json:"name"`
	Share       float64                    `json:"share"`
	Traffic     ScenarioTrafficSummary     `json:"traffic"`
	Outcome     ScenarioOutcomeSummary     `json:"outcome"`
	Performance ScenarioPerformanceSummary `json:"performance"`
	Violations  int                        `json:"violations"`
}

type ScenarioTrafficSummary struct {
	Payments CountSummary `json:"payments"`
	Pacs002  CountSummary `json:"pacs002"`
}

type CountSummary struct {
	Started  int `json:"started"`
	Accepted int `json:"accepted"`
}

type ScenarioOutcomeSummary struct {
	Expected      ExpectedOutcomeSummary `json:"expected"`
	Matched       int                    `json:"matched"`
	Missing       int                    `json:"missing"`
	Contradictory int                    `json:"contradictory"`
}

type ExpectedOutcomeSummary struct {
	Status      string   `json:"status"`
	ReasonCodes []string `json:"reason_codes"`
}

type ScenarioPerformanceSummary struct {
	WithinThreshold int            `json:"within_threshold"`
	AfterThreshold  int            `json:"after_threshold"`
	LatencyMs       LatencySummary `json:"latency_ms"`
}

type PerformanceSummary struct {
	ThresholdMs                   int64            `json:"threshold_ms"`
	ActiveTPS                     ActiveTPSSummary `json:"active_tps"`
	PayerNotificationsAfterActive int              `json:"payer_notifications_after_active"`
	LatencyMs                     LatencySummary   `json:"latency_ms"`
}

type ActiveTPSSummary struct {
	Payments           float64 `json:"payments"`
	Pacs002            float64 `json:"pacs002"`
	Pacs008Replays     float64 `json:"pacs008_replays"`
	Pacs002Replays     float64 `json:"pacs002_replays"`
	PayerNotifications float64 `json:"payer_notifications"`
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
	Warmup         time.Duration
	Duration       time.Duration
	Drain          time.Duration
	Replay         config.Replay
	Scenarios      []config.Scenario
	Window         runwindow.Window
}

func Build(starts []events.Start, notifications []events.Notification, statusStarts []events.StatusStart, replays []events.Replay, options Options) (Summary, error) {
	var summary Summary
	scenarios := options.Scenarios
	if len(scenarios) == 0 {
		return Summary{}, fmt.Errorf("report requires at least one configured scenario")
	}
	if err := validateStartScenarios(starts, scenarios); err != nil {
		return Summary{}, err
	}
	if err := validateStatusStartScenarios(statusStarts, scenarios); err != nil {
		return Summary{}, err
	}
	if err := validateWindow(options.Window); err != nil {
		return Summary{}, err
	}
	summary.Generation.TargetTPS = options.TargetTxRate
	summary.Performance.ThresholdMs = options.SLAThresholdMs
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
			Name:  scenario.Name,
			Share: scenario.Share,
			Outcome: ScenarioOutcomeSummary{
				Expected: ExpectedOutcomeSummary{
					Status:      scenario.Expectations.PayerNotification.Status,
					ReasonCodes: cloneStrings(scenario.Expectations.PayerNotification.ReasonCodes),
				},
			},
		}
	}
	measuredStarts := measuredWindowStarts(starts, options.Window)
	measuredPacs008Replays := measuredWindowReplays(replays, events.MessagePacs008, options.Window)
	measuredPacs002Replays := measuredWindowReplays(replays, events.MessagePacs002, options.Window)
	measuredStatuses := measuredWindowStatusStarts(statusStarts, options.Window)
	summary.Replays.Pacs008 = summarizePacs008Replays(starts, replays, options.Replay.Pacs008, options.Window)
	summary.Replays.Pacs002 = summarizePacs002Replays(statusStarts, replays, options.Replay.Pacs002, options.Window)
	summary.Generation.Started = len(measuredStarts)
	if options.Duration > 0 {
		durationSeconds := options.Duration.Seconds()
		summary.Generation.ActualTPS = roundMetric(float64(len(measuredStarts)) / durationSeconds)
		summary.Performance.ActiveTPS.Payments = summary.Generation.ActualTPS
		summary.Performance.ActiveTPS.Pacs002 = roundMetric(float64(len(measuredStatuses)) / durationSeconds)
		summary.Performance.ActiveTPS.Pacs008Replays = roundMetric(float64(len(measuredPacs008Replays)) / durationSeconds)
		summary.Performance.ActiveTPS.Pacs002Replays = roundMetric(float64(len(measuredPacs002Replays)) / durationSeconds)
		if options.TargetTxRate > 0 {
			summary.Generation.Expected = int(float64(options.TargetTxRate) * durationSeconds)
			summary.Generation.Violations = absoluteDifference(summary.Generation.Expected, summary.Generation.Started)
		}
	}
	for _, start := range starts {
		startedAt := requestStartedAt(start)
		if startedAt < options.Window.GenerationStartedAt.UnixNano() || startedAt >= options.Window.GenerationEndedAt.UnixNano() {
			summary.Generation.Violations++
		}
	}

	payerNotifications := collectPayerNotifications(notifications)
	for _, start := range starts {
		scenario, err := scenarioForStart(start, scenarios)
		if err != nil {
			return Summary{}, err
		}
		scenarioSummary := &summary.Scenarios[scenarioIndexes[scenario.Name]]
		scenarioSummary.Traffic.Payments.Started++
		if start.HTTPStatus < 200 || start.HTTPStatus >= 300 {
			scenarioSummary.Violations++
			continue
		}
		scenarioSummary.Traffic.Payments.Accepted++
		observation := payerNotifications[notificationKey{
			endToEndID: start.EndToEndID,
			ispb:       start.PayerISPB,
		}]
		if len(observation.deliveries) == 0 {
			scenarioSummary.Outcome.Missing++
			scenarioSummary.Violations++
			continue
		}
		match := matchPayerNotification(observation, scenario.Expectations.PayerNotification)
		if match.matched {
			scenarioSummary.Outcome.Matched++
		}
		if match.statusMismatch || match.reasonCodesMismatch {
			scenarioSummary.Outcome.Contradictory++
			scenarioSummary.Violations++
		}
	}

	for _, status := range statusStarts {
		scenarioIndex, err := scenarioIndexForName(status.ScenarioName, scenarios)
		if err != nil {
			return Summary{}, fmt.Errorf("status start %q: %w", status.EndToEndID, err)
		}
		scenarioSummary := &summary.Scenarios[scenarioIndex]
		scenarioSummary.Traffic.Pacs002.Started++
		if status.HTTPStatus >= 200 && status.HTTPStatus < 300 {
			scenarioSummary.Traffic.Pacs002.Accepted++
		} else {
			scenarioSummary.Violations++
		}
		if status.RequestStartedAtNS >= options.Window.ReplayDeadlineAt.UnixNano() {
			scenarioSummary.Violations++
		}
	}

	activeWindowEndNS := options.Window.GenerationEndedAt.UnixNano()
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
		if match.earliestMatchingAt < activeWindowEndNS {
			payerNotifiedDuringActive++
		}
		scenarioSummary := &summary.Scenarios[scenarioIndex]
		if durationMs > float64(options.SLAThresholdMs) {
			scenarioSummary.Performance.AfterThreshold++
		} else {
			scenarioSummary.Performance.WithinThreshold++
		}
	}
	if options.Duration > 0 {
		durationSeconds := options.Duration.Seconds()
		summary.Performance.ActiveTPS.PayerNotifications = roundMetric(float64(payerNotifiedDuringActive) / durationSeconds)
	}
	summary.Performance.PayerNotificationsAfterActive = matchedActivePayments - payerNotifiedDuringActive

	sort.Float64s(durations)
	summary.Performance.LatencyMs = summarizeLatency(durations)
	for index := range summary.Scenarios {
		sort.Float64s(scenarioDurations[index])
		summary.Scenarios[index].Performance.LatencyMs = summarizeLatency(scenarioDurations[index])
	}
	summary.Valid = summary.Generation.Violations == 0 &&
		summary.Replays.Pacs008.Violations == 0 &&
		summary.Replays.Pacs002.Violations == 0 &&
		scenariosAreValid(summary.Scenarios)
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

func validateStatusStartScenarios(statusStarts []events.StatusStart, scenarios []config.Scenario) error {
	for _, status := range statusStarts {
		if _, err := scenarioIndexForName(status.ScenarioName, scenarios); err != nil {
			return fmt.Errorf("status start %q uses unknown scenario name %q", status.EndToEndID, status.ScenarioName)
		}
	}
	return nil
}

func scenarioForStart(start events.Start, scenarios []config.Scenario) (config.Scenario, error) {
	index, err := scenarioIndexForName(start.ScenarioName, scenarios)
	if err == nil {
		return scenarios[index], nil
	}
	return config.Scenario{}, fmt.Errorf("start %q uses unknown scenario name %q", start.EndToEndID, start.ScenarioName)
}

func scenarioIndexForName(name string, scenarios []config.Scenario) (int, error) {
	for index, scenario := range scenarios {
		if scenario.Name == name {
			return index, nil
		}
	}
	return 0, fmt.Errorf("unknown scenario name %q", name)
}

func scenariosAreValid(scenarios []ScenarioSummary) bool {
	for _, scenario := range scenarios {
		if scenario.Violations != 0 {
			return false
		}
	}
	return true
}

func measuredWindowStarts(starts []events.Start, window runwindow.Window) []events.Start {
	windowStart := window.ActiveStartedAt.UnixNano()
	windowEnd := window.GenerationEndedAt.UnixNano()
	measured := make([]events.Start, 0, len(starts))
	for _, start := range starts {
		if requestStartedAt(start) < windowStart {
			continue
		}
		if requestStartedAt(start) >= windowEnd {
			continue
		}
		measured = append(measured, start)
	}
	return measured
}

func measuredWindowReplays(replays []events.Replay, messageType string, window runwindow.Window) []events.Replay {
	windowStart := window.ActiveStartedAt.UnixNano()
	windowEnd := window.GenerationEndedAt.UnixNano()
	measured := make([]events.Replay, 0, len(replays))
	for _, replay := range replays {
		if replay.MessageType != messageType {
			continue
		}
		if replay.RequestStartedAtNS < windowStart {
			continue
		}
		if replay.RequestStartedAtNS >= windowEnd {
			continue
		}
		measured = append(measured, replay)
	}
	return measured
}

func measuredWindowStatusStarts(statusStarts []events.StatusStart, window runwindow.Window) []events.StatusStart {
	windowStart := window.ActiveStartedAt.UnixNano()
	windowEnd := window.GenerationEndedAt.UnixNano()
	measured := make([]events.StatusStart, 0, len(statusStarts))
	for _, status := range statusStarts {
		if status.RequestStartedAtNS >= windowStart && status.RequestStartedAtNS < windowEnd {
			measured = append(measured, status)
		}
	}
	return measured
}

func summarizePacs008Replays(starts []events.Start, replays []events.Replay, configured *config.Pacs008Replay, window runwindow.Window) ReplayTypeSummary {
	var summary ReplayTypeSummary
	startsByID := make(map[string]events.Start, len(starts))
	for _, start := range starts {
		startsByID[start.EndToEndID] = start
	}
	attemptsByID := make(map[string]int, len(replays))
	for _, replay := range replays {
		if replay.MessageType != events.MessagePacs008 {
			continue
		}
		summary.Started++
		if replay.HTTPStatus >= 200 && replay.HTTPStatus < 300 {
			summary.Accepted++
		} else {
			summary.Violations++
		}
		start, exists := startsByID[replay.EndToEndID]
		if !exists {
			summary.Violations++
			continue
		}
		attemptsByID[start.EndToEndID]++
		if !start.Pacs008ReplaySelected {
			summary.Violations++
		}
		if replay.SenderISPB != start.PayerISPB || replay.ScenarioName != start.ScenarioName {
			summary.Violations++
		}
		if configured == nil {
			summary.Violations++
			continue
		}
		if replay.RequestStartedAtNS < requestStartedAt(start)+configured.Delay.Nanoseconds() {
			summary.Violations++
		}
		if replay.RequestStartedAtNS >= window.ReplayDeadlineAt.UnixNano() {
			summary.Violations++
		}
	}
	for _, start := range starts {
		if !start.Pacs008ReplaySelected {
			continue
		}
		attempts := attemptsByID[start.EndToEndID]
		if attempts == 0 {
			summary.Violations++
		}
		if attempts > 1 {
			summary.Violations += attempts - 1
		}
	}
	return summary
}

func summarizePacs002Replays(statusStarts []events.StatusStart, replays []events.Replay, configured *config.Pacs002Replay, window runwindow.Window) ReplayTypeSummary {
	var summary ReplayTypeSummary
	statusesByID := make(map[string]events.StatusStart, len(statusStarts))
	for _, status := range statusStarts {
		statusesByID[status.EndToEndID] = status
	}
	attemptsByID := make(map[string]int)
	for _, replay := range replays {
		if replay.MessageType != events.MessagePacs002 {
			continue
		}
		summary.Started++
		if replay.HTTPStatus >= 200 && replay.HTTPStatus < 300 {
			summary.Accepted++
		} else {
			summary.Violations++
		}
		status, exists := statusesByID[replay.EndToEndID]
		if !exists {
			summary.Violations++
			continue
		}
		attemptsByID[replay.EndToEndID]++
		if !status.Pacs002ReplaySelected || replay.SenderISPB != status.SenderISPB || replay.ScenarioName != status.ScenarioName {
			summary.Violations++
		}
		if configured == nil {
			summary.Violations++
			continue
		}
		if replay.RequestStartedAtNS < status.RequestStartedAtNS+configured.Delay.Nanoseconds() || replay.RequestStartedAtNS >= window.ReplayDeadlineAt.UnixNano() {
			summary.Violations++
		}
	}
	for _, status := range statusStarts {
		if !status.Pacs002ReplaySelected {
			continue
		}
		attempts := attemptsByID[status.EndToEndID]
		if attempts == 0 {
			summary.Violations++
		}
		if attempts > 1 {
			summary.Violations += attempts - 1
		}
	}
	return summary
}

func validateWindow(window runwindow.Window) error {
	if window.GenerationStartedAt.IsZero() || window.ActiveStartedAt.Before(window.GenerationStartedAt) || window.GenerationEndedAt.Before(window.ActiveStartedAt) || window.ReplayDeadlineAt.Before(window.GenerationEndedAt) {
		return fmt.Errorf("report requires a valid authoritative run window")
	}
	return nil
}

func absoluteDifference(left, right int) int {
	if left > right {
		return left - right
	}
	return right - left
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

func Print(startsPath string, eventsPath string, statusStartsPath string, replaysPath string, options Options, output io.Writer) error {
	starts, err := events.ReadStarts(startsPath)
	if err != nil {
		return err
	}
	notifications, err := events.ReadNotifications(eventsPath)
	if err != nil {
		return err
	}
	replays, err := events.ReadReplays(replaysPath)
	if err != nil {
		return err
	}
	statusStarts, err := events.ReadStatusStarts(statusStartsPath)
	if err != nil {
		return err
	}

	summary, err := Build(starts, notifications, statusStarts, replays, options)
	if err != nil {
		return err
	}
	encoder := json.NewEncoder(output)
	encoder.SetIndent("", "  ")
	return encoder.Encode(summary)
}

func summarizeLatency(durations []float64) LatencySummary {
	summary := LatencySummary{
		P50: roundMetric(percentile(durations, 0.50)),
		P95: roundMetric(percentile(durations, 0.95)),
		P99: roundMetric(percentile(durations, 0.99)),
	}
	if len(durations) > 0 {
		summary.Max = roundMetric(durations[len(durations)-1])
	}
	return summary
}

func roundMetric(value float64) float64 {
	return math.Round(value*1000) / 1000
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
