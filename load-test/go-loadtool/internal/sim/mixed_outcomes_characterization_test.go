package sim

import (
	"testing"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/config"
)

func TestMixedOutcomesSmokeCharacterizesWorkloadPopulations(t *testing.T) {
	cfg := Config{
		TargetTxRate: 100,
		Warmup:       5 * time.Second,
		Duration:     10 * time.Second,
		Scenarios:    mixedPlannerScenarios(),
		Replay: config.Replay{
			Pacs008: &config.Pacs008Replay{Share: 0.05, Delay: 10 * time.Second},
			Pacs002: &config.Pacs002Replay{Share: 0.05, Delay: 10 * time.Second},
		},
	}

	originalCount, err := maximumGeneratedTransfers(cfg.TargetTxRate, cfg.Warmup, cfg.Duration)
	if err != nil {
		t.Fatal(err)
	}
	planner, err := newWorkloadPlanner(cfg.Scenarios)
	if err != nil {
		t.Fatal(err)
	}
	pacs008Selector, err := newReplaySelectorWithDomain(cfg.Replay.Pacs008.Share, pacs008ReplayShuffleDomain)
	if err != nil {
		t.Fatal(err)
	}
	pacs002Selector, err := newReplaySelectorWithDomain(cfg.Replay.Pacs002.Share, pacs002ReplayShuffleDomain)
	if err != nil {
		t.Fatal(err)
	}

	scenarioCounts := map[string]int{}
	pacs008Selected := 0
	pacs002OriginalsStarted := 0
	pacs002Selected := 0
	for range originalCount {
		planned := planner.Next()
		scenarioCounts[planned.ScenarioName]++
		if pacs008Selector.Next() {
			pacs008Selected++
		}

		// In this characterized workload every original produces one original
		// PACS.002. The PACS.002 selector advances on that status population,
		// not on PACS.008 replay attempts.
		pacs002OriginalsStarted++
		if pacs002Selector.Next() {
			pacs002Selected++
		}
	}

	if originalCount != 1_250 {
		t.Fatalf("original payments = %d, want 1250", originalCount)
	}
	if activeOriginals := cfg.TargetTxRate * int(cfg.Duration/time.Second); activeOriginals != 1_000 {
		t.Fatalf("active original payments = %d, want 1000", activeOriginals)
	}
	if scenarioCounts["happy-path"] != 1_000 || scenarioCounts["insufficient-funds"] != 250 {
		t.Fatalf("scenario populations = %#v, want happy-path=1000 insufficient-funds=250", scenarioCounts)
	}
	if pacs002OriginalsStarted != 1_250 {
		t.Fatalf("PACS.002 originals started = %d, want 1250", pacs002OriginalsStarted)
	}
	if pacs008Selected != 64 || pacs002Selected != 63 {
		t.Fatalf("selected replays = pacs.008:%d pacs.002:%d, want 64/63", pacs008Selected, pacs002Selected)
	}
}
