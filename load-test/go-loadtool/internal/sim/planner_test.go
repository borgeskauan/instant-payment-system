package sim

import (
	"math"
	"reflect"
	"strings"
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
	var firstNames []string
	var secondNames []string
	counts := map[string]int{}
	for range 200 {
		firstTransfer := first.Next()
		secondTransfer := second.Next()
		firstNames = append(firstNames, firstTransfer.ScenarioName)
		secondNames = append(secondNames, secondTransfer.ScenarioName)
		counts[firstTransfer.ScenarioName]++
	}
	if !reflect.DeepEqual(firstNames, secondNames) {
		t.Fatal("repeated planners did not reproduce scenario order")
	}
	if counts["happy-path"] != 160 || counts["insufficient-funds"] != 40 {
		t.Fatalf("scenario counts = %#v, want exact 80/20 per block", counts)
	}
	for blockIndex := range 2 {
		blockCounts := map[string]int{}
		for _, scenarioName := range firstNames[blockIndex*100 : (blockIndex+1)*100] {
			blockCounts[scenarioName]++
		}
		if blockCounts["happy-path"] != 80 || blockCounts["insufficient-funds"] != 20 {
			t.Fatalf("block %d counts = %#v, want 80/20", blockIndex, blockCounts)
		}
	}
	if reflect.DeepEqual(firstNames[:100], firstNames[100:]) {
		t.Fatal("successive blocks were not independently shuffled")
	}
	encodePrefix := func(names []string) string {
		encoded := make([]byte, len(names))
		for index, scenarioName := range names {
			if scenarioName == "happy-path" {
				encoded[index] = 'H'
			} else {
				encoded[index] = 'I'
			}
		}
		return string(encoded)
	}
	if got := encodePrefix(firstNames[:20]); got != "HHIIHHHIHHHHHHIHHHHH" {
		t.Fatalf("block 0 prefix = %q", got)
	}
	if got := encodePrefix(firstNames[100:120]); got != "HIIHHHHHHHHIHHHIHHHI" {
		t.Fatalf("block 1 prefix = %q", got)
	}
}

func TestReplaySelectorUsesConfiguredExactDeterministicBlocks(t *testing.T) {
	for _, share := range []float64{0.10, 0.25} {
		first, err := newReplaySelector(share)
		if err != nil {
			t.Fatal(err)
		}
		second, err := newReplaySelector(share)
		if err != nil {
			t.Fatal(err)
		}
		wantPerBlock := int(share * config.ScenarioSelectionBlockSize)
		for blockIndex := range 2 {
			selected := 0
			for range config.ScenarioSelectionBlockSize {
				firstSelected := first.Next()
				if firstSelected != second.Next() {
					t.Fatalf("share %.2f block %d was not deterministic", share, blockIndex)
				}
				if firstSelected {
					selected++
				}
			}
			if selected != wantPerBlock {
				t.Fatalf("share %.2f block %d selected %d, want %d", share, blockIndex, selected, wantPerBlock)
			}
		}
	}
}

func TestReplaySelectionDoesNotChangeScenarioOrdering(t *testing.T) {
	withReplay, err := newWorkloadPlanner(mixedPlannerScenarios())
	if err != nil {
		t.Fatal(err)
	}
	withoutReplay, err := newWorkloadPlanner(mixedPlannerScenarios())
	if err != nil {
		t.Fatal(err)
	}
	replay, err := newReplaySelector(0.10)
	if err != nil {
		t.Fatal(err)
	}
	for sequence := range 300 {
		_ = replay.Next()
		got := withReplay.Next()
		want := withoutReplay.Next()
		if !reflect.DeepEqual(got, want) {
			t.Fatalf("sequence %d differs with replay selection: %#v / %#v", sequence, got, want)
		}
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
		wantAmount := int64(100) + localCounts[transfer.ScenarioName]%3
		if transfer.Amount != wantAmount {
			t.Fatalf("%s amount = %d, want %d", transfer.ScenarioName, transfer.Amount, wantAmount)
		}
		localCounts[transfer.ScenarioName]++
	}
	if localCounts["happy-path"] != 80 || localCounts["insufficient-funds"] != 20 {
		t.Fatalf("local counts = %#v", localCounts)
	}
}

func TestMaximumGeneratedTransfersExcludesFinalBoundaryTransfer(t *testing.T) {
	got, err := maximumGeneratedTransfers(100, 5*time.Second, 10*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if got != 1250 {
		t.Fatalf("maximumGeneratedTransfers = %d, want 1250", got)
	}
}

func TestPacs002ReplaySelectorAppliesExactQuotaToStatusSequence(t *testing.T) {
	selector, err := newReplaySelectorWithDomain(0.10, pacs002ReplayShuffleDomain)
	if err != nil {
		t.Fatal(err)
	}
	selected := 0
	for range 2_000 {
		if selector.Next() {
			selected++
		}
	}
	if selected != 200 {
		t.Fatalf("selected PACS.002 replays = %d, want 200 of 2,000 status originals", selected)
	}
}

func TestPacs008AndPacs002ReplaySelectorsUseDistinctDomains(t *testing.T) {
	pacs008, err := newReplaySelectorWithDomain(0.10, pacs008ReplayShuffleDomain)
	if err != nil {
		t.Fatal(err)
	}
	pacs002, err := newReplaySelectorWithDomain(0.10, pacs002ReplayShuffleDomain)
	if err != nil {
		t.Fatal(err)
	}
	same := true
	for range config.ScenarioSelectionBlockSize {
		if pacs008.Next() != pacs002.Next() {
			same = false
		}
	}
	if same {
		t.Fatal("PACS.008 and PACS.002 replay selections are identical")
	}
}

func TestMaximumGeneratedTransfersRejectsCountMultiplicationOverflow(t *testing.T) {
	_, err := maximumGeneratedTransfers(1<<60, 0, 16*time.Second)
	if err == nil || !strings.Contains(err.Error(), "too many transfers") {
		t.Fatalf("maximumGeneratedTransfers error = %v, want too many transfers", err)
	}
}

func TestMaximumGeneratedTransfersRejectsRateThatOverflowsQueueCapacity(t *testing.T) {
	_, err := maximumGeneratedTransfers(math.MaxInt/4+1, 0, time.Second)
	if err == nil || !strings.Contains(err.Error(), "rate is too large") {
		t.Fatalf("maximumGeneratedTransfers error = %v, want rate is too large", err)
	}
}

func TestDerivedProvisioningFollowsExplicitFundingPolicies(t *testing.T) {
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
	if plan[0].Name != "happy-path" || plan[0].PayerBalance == "0.00" || plan[0].ReceiverBalance != "0.00" || !plan[0].ResetIfExists {
		t.Fatalf("happy-path provisioning = %#v", plan[0])
	}
	if plan[1].Name != "insufficient-funds" || plan[1].PayerBalance != "0.00" || plan[1].ReceiverBalance != "0.00" || !plan[1].ResetIfExists {
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
		if transfer.ScenarioName == "happy-path" {
			debits[transfer.Pair.Payer] += transfer.Amount
		}
	}
	minimumBucketBalance := plan[0].payerBalanceCents / settlementBucketCount
	for payer, debit := range debits {
		if minimumBucketBalance < debit {
			t.Fatalf("payer %s can debit %d but smallest bucket has %d", payer, debit, minimumBucketBalance)
		}
	}
}

func TestDerivedProvisioningDoesNotDependOnScenarioName(t *testing.T) {
	first := Config{TargetTxRate: 100, Warmup: 5 * time.Second, Duration: 10 * time.Second, Scenarios: mixedPlannerScenarios()[:1]}
	first.Scenarios[0].Share = 1
	second := first
	second.Scenarios = append([]config.Scenario(nil), first.Scenarios...)
	second.Scenarios[0].Name = "renamed-workload"

	firstPlan, err := DeriveProvisioning(first)
	if err != nil {
		t.Fatal(err)
	}
	secondPlan, err := DeriveProvisioning(second)
	if err != nil {
		t.Fatal(err)
	}
	firstPlan[0].Name = secondPlan[0].Name
	if !reflect.DeepEqual(firstPlan, secondPlan) {
		t.Fatalf("renaming changed provisioning: %#v / %#v", firstPlan, secondPlan)
	}
}

func mixedPlannerScenarios() []config.Scenario {
	participants := func(pairNumberStart, hot, cold int) config.HotColdPairDistribution {
		return config.HotColdPairDistribution{PairNumberStart: pairNumberStart, HotPairCount: hot, ColdPairCount: cold, HotTrafficShare: 0.8}
	}
	amount := config.SequentialRangeAmount{Minimum: 100, Maximum: 102}
	return []config.Scenario{
		{
			Name:         "happy-path",
			Share:        0.8,
			Participants: participants(1, 8, 32),
			Amount:       amount,
			Funding: config.ScenarioFunding{
				Payer:         config.FundingAccount{Mode: config.FundingCoverGeneratedDebits},
				Receiver:      config.FundingAccount{Mode: config.FundingFixed, Balance: "0.00"},
				ResetIfExists: true,
			},
		},
		{
			Name:         "insufficient-funds",
			Share:        0.2,
			Participants: participants(41, 2, 8),
			Amount:       amount,
			Funding: config.ScenarioFunding{
				Payer:         config.FundingAccount{Mode: config.FundingFixed, Balance: "0.00"},
				Receiver:      config.FundingAccount{Mode: config.FundingFixed, Balance: "0.00"},
				ResetIfExists: true,
			},
		},
	}
}
