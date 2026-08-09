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
	Run                 RunSummary         `json:"run"`
	Transactions        TransactionSummary `json:"transactions"`
	ThroughputPerSecond ThroughputSummary  `json:"throughput_per_second"`
	LatencyMs           LatencySummary     `json:"latency_ms"`
	Scenarios           []ScenarioSummary  `json:"scenarios"`
	Diagnostics         DiagnosticSummary  `json:"diagnostics"`
}

type ScenarioSummary struct {
	Type            string                     `json:"type"`
	ConfiguredShare float64                    `json:"configured_share"`
	Transactions    ScenarioTransactionSummary `json:"transactions"`
	LatencyMs       LatencySummary             `json:"latency_ms"`
}

type ScenarioTransactionSummary struct {
	Started           int                            `json:"started"`
	Accepted          int                            `json:"accepted"`
	HTTPStatus        ExpectationMatchSummary        `json:"http_status"`
	PayerConfirmation ConfirmationExpectationSummary `json:"payer_confirmation"`
	ConfirmedBySLA    ConfirmedBySLASummary          `json:"confirmed_by_sla"`
	Violations        int                            `json:"violations"`
}

type ExpectationMatchSummary struct {
	Expectation string `json:"expectation"`
	Matched     int    `json:"matched"`
	Violations  int    `json:"violations"`
}

type ConfirmationExpectationSummary struct {
	Expectation string `json:"expectation"`
	Eligible    int    `json:"eligible"`
	Received    int    `json:"received"`
	Absent      int    `json:"absent"`
	Violations  int    `json:"violations"`
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
		httpExpectation, confirmationExpectation, ok := scenario.Expectations()
		if !ok {
			return Summary{}, fmt.Errorf("unsupported configured scenario type %q", scenario.Type)
		}
		scenarioIndexes[scenario.Type] = index
		summary.Scenarios[index] = ScenarioSummary{
			Type:            scenario.Type,
			ConfiguredShare: scenario.Share,
			Transactions: ScenarioTransactionSummary{
				HTTPStatus:        ExpectationMatchSummary{Expectation: httpExpectation},
				PayerConfirmation: ConfirmationExpectationSummary{Expectation: confirmationExpectation},
			},
		}
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
		scenario, err := scenarioForStart(start, scenarios)
		if err != nil {
			return Summary{}, err
		}
		httpExpectation, confirmationExpectation, _ := scenario.Expectations()
		if httpExpectation != config.ExpectedHTTP2xx {
			return Summary{}, fmt.Errorf("unsupported HTTP expectation %q for scenario %q", httpExpectation, scenario.Type)
		}
		scenarioSummary := &summary.Scenarios[scenarioIndexes[scenario.Type]]
		scenarioSummary.Transactions.Started++
		if start.HTTPStatus < 200 || start.HTTPStatus >= 300 {
			scenarioSummary.Transactions.HTTPStatus.Violations++
			scenarioSummary.Transactions.Violations++
			continue
		}
		summary.Transactions.Accepted++
		scenarioSummary.Transactions.Accepted++
		scenarioSummary.Transactions.HTTPStatus.Matched++
		scenarioSummary.Transactions.PayerConfirmation.Eligible++
		receivedAt, ok := confirmations[confirmationKey{
			endToEndID: start.EndToEndID,
			ispb:       start.PayerISPB,
		}]
		if ok {
			scenarioSummary.Transactions.PayerConfirmation.Received++
		} else {
			scenarioSummary.Transactions.PayerConfirmation.Absent++
		}
		switch confirmationExpectation {
		case config.ConfirmationRequired:
			if !ok {
				summary.Transactions.Confirmation.NotConfirmed++
				scenarioSummary.Transactions.PayerConfirmation.Violations++
				scenarioSummary.Transactions.Violations++
				continue
			}
		case config.ConfirmationForbidden:
			if ok {
				scenarioSummary.Transactions.PayerConfirmation.Violations++
				scenarioSummary.Transactions.Violations++
			}
			continue
		default:
			return Summary{}, fmt.Errorf("unsupported payer confirmation expectation %q for scenario %q", confirmationExpectation, scenario.Type)
		}
		durationMs := float64(receivedAt-requestStartedAt(start)) / 1_000_000
		durations = append(durations, durationMs)
		scenarioIndex := scenarioIndexes[scenario.Type]
		scenarioDurations[scenarioIndex] = append(scenarioDurations[scenarioIndex], durationMs)
		summary.Transactions.Confirmation.Confirmed++
		if activeWindowEndNS > 0 && receivedAt <= activeWindowEndNS {
			confirmedDuringActive++
		}
		if durationMs > float64(options.SLAThresholdMs) {
			summary.Transactions.ConfirmedBySLA.AfterSLA++
			scenarioSummary.Transactions.ConfirmedBySLA.AfterSLA++
		} else {
			summary.Transactions.ConfirmedBySLA.WithinSLA++
			scenarioSummary.Transactions.ConfirmedBySLA.WithinSLA++
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
	for index := range summary.Scenarios {
		sort.Float64s(scenarioDurations[index])
		summary.Scenarios[index].LatencyMs.P50 = percentile(scenarioDurations[index], 0.50)
		summary.Scenarios[index].LatencyMs.P95 = percentile(scenarioDurations[index], 0.95)
		summary.Scenarios[index].LatencyMs.P99 = percentile(scenarioDurations[index], 0.99)
		if len(scenarioDurations[index]) > 0 {
			summary.Scenarios[index].LatencyMs.Max = scenarioDurations[index][len(scenarioDurations[index])-1]
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
	scenarioType := start.ScenarioType
	if scenarioType == "" && len(scenarios) == 1 {
		scenarioType = scenarios[0].Type
	}
	for _, scenario := range scenarios {
		if scenario.Type != scenarioType {
			continue
		}
		if _, _, ok := scenario.Expectations(); !ok {
			return config.Scenario{}, fmt.Errorf("unsupported configured scenario type %q", scenario.Type)
		}
		return scenario, nil
	}
	return config.Scenario{}, fmt.Errorf("start %q uses unknown scenario type %q", start.EndToEndID, scenarioType)
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
