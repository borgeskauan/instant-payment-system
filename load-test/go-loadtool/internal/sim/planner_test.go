package sim

import (
	"reflect"
	"testing"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/config"
)

func TestPlannerUsesExactDeterministicScenarioBlocks(t *testing.T) {
	scenarios := mixedPlannerScenarios()
	first, err := newWorkloadPlanner(scenarios)
	if err != nil {
		t.Fatal(err)
	}
	second, err := newWorkloadPlanner(scenarios)
	if err != nil {
		t.Fatal(err)
	}
	var firstTypes []string
	var secondTypes []string
	counts := map[string]int{}
	for range 200 {
		firstTransfer := first.Next()
		secondTransfer := second.Next()
		firstTypes = append(firstTypes, firstTransfer.ScenarioType)
		secondTypes = append(secondTypes, secondTransfer.ScenarioType)
		counts[firstTransfer.ScenarioType]++
	}
	if !reflect.DeepEqual(firstTypes, secondTypes) {
		t.Fatal("repeated planners did not reproduce scenario order")
	}
	if counts[config.ScenarioHappyPath] != 160 || counts[config.ScenarioInsufficientFunds] != 40 {
		t.Fatalf("scenario counts = %#v, want exact 80/20 per block", counts)
	}
	for blockIndex := range 2 {
		blockCounts := map[string]int{}
		for _, scenarioType := range firstTypes[blockIndex*100 : (blockIndex+1)*100] {
			blockCounts[scenarioType]++
		}
		if blockCounts[config.ScenarioHappyPath] != 80 || blockCounts[config.ScenarioInsufficientFunds] != 20 {
			t.Fatalf("block %d counts = %#v, want 80/20", blockIndex, blockCounts)
		}
	}
	if reflect.DeepEqual(firstTypes[:100], firstTypes[100:]) {
		t.Fatal("successive blocks were not independently shuffled")
	}
	encodePrefix := func(types []string) string {
		encoded := make([]byte, len(types))
		for index, scenarioType := range types {
			if scenarioType == config.ScenarioHappyPath {
				encoded[index] = 'H'
			} else {
				encoded[index] = 'I'
			}
		}
		return string(encoded)
	}
	if got := encodePrefix(firstTypes[:20]); got != "HHIIHHHIHHHHHHIHHHHH" {
		t.Fatalf("block 0 prefix = %q", got)
	}
	if got := encodePrefix(firstTypes[100:120]); got != "HIIHHHHHHHHIHHHIHHHI" {
		t.Fatalf("block 1 prefix = %q", got)
	}
}

func TestPlannerKeepsScenarioLocalPairAndAmountSequences(t *testing.T) {
	planner, err := newWorkloadPlanner(mixedPlannerScenarios())
	if err != nil {
		t.Fatal(err)
	}
	localCounts := map[string]int64{}
	for range 100 {
		transfer := planner.Next()
		wantAmount := int64(100) + localCounts[transfer.ScenarioType]%3
		if transfer.Amount != wantAmount {
			t.Fatalf("%s amount = %d, want %d", transfer.ScenarioType, transfer.Amount, wantAmount)
		}
		localCounts[transfer.ScenarioType]++
	}
	if localCounts[config.ScenarioHappyPath] != 80 || localCounts[config.ScenarioInsufficientFunds] != 20 {
		t.Fatalf("local counts = %#v", localCounts)
	}
}

func TestMaximumGeneratedTransfersIncludesFinalBoundaryTransfer(t *testing.T) {
	got, err := maximumGeneratedTransfers(100, 5*time.Second, 10*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if got != 1251 {
		t.Fatalf("maximumGeneratedTransfers = %d, want 1251", got)
	}
}

func TestDerivedProvisioningFundsOnlyHappyPathPayers(t *testing.T) {
	cfg := Config{
		TargetTxRate: 100,
		Warmup:       5 * time.Second,
		Duration:     10 * time.Second,
		Scenarios:    mixedPlannerScenarios(),
	}
	plan, err := DeriveProvisioning(cfg)
	if err != nil {
		t.Fatal(err)
	}
	repeatedPlan, err := DeriveProvisioning(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(plan, repeatedPlan) {
		t.Fatalf("repeated provisioning differs: %#v / %#v", plan, repeatedPlan)
	}
	if len(plan) != 2 {
		t.Fatalf("plan = %#v", plan)
	}
	if plan[0].Type != config.ScenarioHappyPath || plan[0].PayerBalance <= 0 || plan[0].ReceiverBalance != 0 || !plan[0].ResetIfExists {
		t.Fatalf("happy-path provisioning = %#v", plan[0])
	}
	if plan[1].Type != config.ScenarioInsufficientFunds || plan[1].PayerBalance != 0 || plan[1].ReceiverBalance != 0 || !plan[1].ResetIfExists {
		t.Fatalf("insufficient-funds provisioning = %#v", plan[1])
	}
	planner, err := newWorkloadPlanner(cfg.Scenarios)
	if err != nil {
		t.Fatal(err)
	}
	transferCount, err := maximumGeneratedTransfers(cfg.TargetTxRate, cfg.Warmup, cfg.Duration)
	if err != nil {
		t.Fatal(err)
	}
	debits := map[string]int64{}
	for range transferCount {
		transfer := planner.Next()
		if transfer.ScenarioType == config.ScenarioHappyPath {
			debits[transfer.Pair.Payer] += transfer.Amount
		}
	}
	minimumBucketBalance := plan[0].PayerBalance * 100 / settlementBucketCount
	for payer, debit := range debits {
		if minimumBucketBalance < debit {
			t.Fatalf("payer %s can debit %d but smallest bucket has %d", payer, debit, minimumBucketBalance)
		}
	}
}

func mixedPlannerScenarios() []config.Scenario {
	participants := func(pairNumberStart, hot, cold int) config.HotColdPairDistribution {
		return config.HotColdPairDistribution{PairNumberStart: pairNumberStart, HotPairCount: hot, ColdPairCount: cold, HotTrafficShare: 0.8}
	}
	amount := config.SequentialRangeAmount{Minimum: 100, Maximum: 102}
	return []config.Scenario{
		{
			Type:  config.ScenarioHappyPath,
			Share: 0.8,
			HappyPath: &config.HappyPathScenario{
				Participants: participants(1, 8, 32),
				Amount:       amount,
			},
		},
		{
			Type:  config.ScenarioInsufficientFunds,
			Share: 0.2,
			InsufficientFunds: &config.InsufficientFundsScenario{
				Participants: participants(41, 2, 8),
				Amount:       amount,
			},
		},
	}
}
