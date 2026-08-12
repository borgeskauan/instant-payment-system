package report

import (
	"encoding/json"
	"fmt"
	"io"
	"sort"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/config"
	"instant-payment-system/load-test/go-loadtool/internal/events"
	"instant-payment-system/load-test/go-loadtool/internal/runwindow"
)

type Summary struct {
	Run                        RunSummary            `json:"run"`
	Transactions               TransactionSummary    `json:"transactions"`
	Replays                    ReplaySummary         `json:"replays"`
	StatusMessages             StatusMessageSummary  `json:"status_messages"`
	LoadGeneration             LoadGenerationSummary `json:"load_generation"`
	ThroughputPerSecond        ThroughputSummary     `json:"throughput_per_second"`
	PayerNotificationLatencyMs LatencySummary        `json:"payer_notification_latency_ms"`
	Scenarios                  []ScenarioSummary     `json:"scenarios"`
	Diagnostics                DiagnosticSummary     `json:"diagnostics"`
}

type ReplaySummary struct {
	Pacs008 ReplayTypeSummary `json:"pacs008"`
	Pacs002 ReplayTypeSummary `json:"pacs002"`
}

type StatusMessageSummary struct {
	Pacs002 ReplayTypeSummary `json:"pacs002"`
}

type LoadGenerationSummary struct {
	Expected   int `json:"expected"`
	Started    int `json:"started"`
	Violations int `json:"violations"`
}

type ReplayTypeSummary struct {
	Attempted  int `json:"attempted"`
	Accepted   int `json:"accepted"`
	Violations int `json:"violations"`
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
	OriginalPaymentsStarted   float64 `json:"original_payments_started"`
	Pacs002StatusesStarted    float64 `json:"pacs002_statuses_started"`
	Pacs008ReplaysStarted     float64 `json:"pacs008_replays_started"`
	Pacs002ReplaysStarted     float64 `json:"pacs002_replays_started"`
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
	if err := validateWindow(options.Window); err != nil {
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
	measuredStarts := measuredWindowStarts(starts, options.Window)
	measuredPacs008Replays := measuredWindowReplays(replays, events.MessagePacs008, options.Window)
	measuredPacs002Replays := measuredWindowReplays(replays, events.MessagePacs002, options.Window)
	measuredStatuses := measuredWindowStatusStarts(statusStarts, options.Window)
	summary.Transactions.Started = len(starts)
	summary.StatusMessages.Pacs002 = summarizePacs002StatusStarts(statusStarts, options.Window)
	summary.Replays.Pacs008 = summarizePacs008Replays(starts, replays, options.Replay.Pacs008, options.Window)
	summary.Replays.Pacs002 = summarizePacs002Replays(statusStarts, replays, options.Replay.Pacs002, options.Window)
	if options.Duration > 0 {
		durationSeconds := options.Duration.Seconds()
		originalRate := float64(len(measuredStarts)) / durationSeconds
		summary.ThroughputPerSecond.Started = originalRate
		summary.ThroughputPerSecond.OriginalPaymentsStarted = originalRate
		summary.ThroughputPerSecond.Pacs002StatusesStarted = float64(len(measuredStatuses)) / durationSeconds
		summary.ThroughputPerSecond.Pacs008ReplaysStarted = float64(len(measuredPacs008Replays)) / durationSeconds
		summary.ThroughputPerSecond.Pacs002ReplaysStarted = float64(len(measuredPacs002Replays)) / durationSeconds
		if options.TargetTxRate > 0 {
			summary.LoadGeneration.Expected = int(float64(options.TargetTxRate) * durationSeconds)
			summary.LoadGeneration.Started = len(measuredStarts)
			summary.LoadGeneration.Violations = absoluteDifference(summary.LoadGeneration.Expected, summary.LoadGeneration.Started)
		}
	}
	for _, start := range starts {
		startedAt := requestStartedAt(start)
		if startedAt < options.Window.GenerationStartedAt.UnixNano() || startedAt >= options.Window.GenerationEndedAt.UnixNano() {
			summary.LoadGeneration.Violations++
		}
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
		summary.Attempted++
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

func summarizePacs002StatusStarts(statusStarts []events.StatusStart, window runwindow.Window) ReplayTypeSummary {
	summary := ReplayTypeSummary{Attempted: len(statusStarts)}
	for _, status := range statusStarts {
		if status.HTTPStatus >= 200 && status.HTTPStatus < 300 {
			summary.Accepted++
		} else {
			summary.Violations++
		}
		if status.RequestStartedAtNS >= window.ReplayDeadlineAt.UnixNano() {
			summary.Violations++
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
		summary.Attempted++
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
