package sim

import (
	"fmt"
	"math"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/config"
	"instant-payment-system/load-test/go-loadtool/internal/ids"
)

const settlementBucketCount = 16

type plannedTransfer struct {
	ScenarioIndex int
	ScenarioType  string
	Pair          ids.Pair
	Amount        int64
}

type workloadPlanner struct {
	scenarios     []config.Scenario
	pairs         [][]ids.Pair
	localOrdinals []uint64
	blockIndex    uint64
	block         []int
	blockOffset   int
}

type ProvisioningScenario struct {
	Type            string
	PayerBalance    int64
	ReceiverBalance int64
	ResetIfExists   bool
}

func newWorkloadPlanner(scenarios []config.Scenario) (*workloadPlanner, error) {
	if len(scenarios) == 0 {
		return nil, fmt.Errorf("at least one scenario is required")
	}
	planner := &workloadPlanner{
		scenarios:     scenarios,
		pairs:         make([][]ids.Pair, len(scenarios)),
		localOrdinals: make([]uint64, len(scenarios)),
	}
	totalQuota := 0
	for index, scenario := range scenarios {
		participants, ok := scenario.Participants()
		if !ok {
			return nil, fmt.Errorf("unsupported configured scenario type %q", scenario.Type)
		}
		amount, ok := scenario.Amount()
		if !ok || amount.Minimum <= 0 || amount.Maximum < amount.Minimum {
			return nil, fmt.Errorf("scenario %q requires a valid amount range", scenario.Type)
		}
		if participants.PairNumberStart <= 0 || participants.HotPairCount <= 0 || participants.ColdPairCount <= 0 || participants.HotTrafficShare <= 0 || participants.HotTrafficShare >= 1 {
			return nil, fmt.Errorf("scenario %q requires a valid participant range", scenario.Type)
		}
		quota := int(math.Round(scenario.Share * config.ScenarioSelectionBlockSize))
		if scenario.Share <= 0 || math.Abs(scenario.Share*config.ScenarioSelectionBlockSize-float64(quota)) > 1e-9 {
			return nil, fmt.Errorf("scenario %q share must select a whole number of entries in a %d-entry block", scenario.Type, config.ScenarioSelectionBlockSize)
		}
		totalQuota += quota
		planner.pairs[index] = buildPairs(participants.PairNumberStart, participants.HotPairCount+participants.ColdPairCount)
	}
	if totalQuota != config.ScenarioSelectionBlockSize {
		return nil, fmt.Errorf("scenario shares must sum to 1.0")
	}
	return planner, nil
}

func (planner *workloadPlanner) Next() plannedTransfer {
	if planner.blockOffset >= len(planner.block) {
		planner.block = planner.shuffledBlock(planner.blockIndex)
		planner.blockIndex++
		planner.blockOffset = 0
	}
	scenarioIndex := planner.block[planner.blockOffset]
	planner.blockOffset++
	localOrdinal := planner.localOrdinals[scenarioIndex]
	planner.localOrdinals[scenarioIndex]++
	scenario := planner.scenarios[scenarioIndex]
	participants, _ := scenario.Participants()
	amountRange, _ := scenario.Amount()

	hotCount := participants.HotPairCount
	coldEvery := int(1 / (1 - participants.HotTrafficShare))
	if coldEvery < 2 {
		coldEvery = 2
	}
	pairIndex := hotCount + int(localOrdinal)%participants.ColdPairCount
	if localOrdinal%uint64(coldEvery) != 0 {
		pairIndex = int(localOrdinal) % hotCount
	}
	amountCount := amountRange.Maximum - amountRange.Minimum + 1
	return plannedTransfer{
		ScenarioIndex: scenarioIndex,
		ScenarioType:  scenario.Type,
		Pair:          planner.pairs[scenarioIndex][pairIndex],
		Amount:        amountRange.Minimum + int64(localOrdinal%uint64(amountCount)),
	}
}

func (planner *workloadPlanner) shuffledBlock(blockIndex uint64) []int {
	block := make([]int, 0, config.ScenarioSelectionBlockSize)
	for scenarioIndex, scenario := range planner.scenarios {
		quota := int(math.Round(scenario.Share * config.ScenarioSelectionBlockSize))
		for range quota {
			block = append(block, scenarioIndex)
		}
	}
	random := splitMix64{state: (blockIndex + 1) * 0x9e3779b97f4a7c15}
	for index := len(block) - 1; index > 0; index-- {
		swapIndex := int(random.next() % uint64(index+1))
		block[index], block[swapIndex] = block[swapIndex], block[index]
	}
	return block
}

type splitMix64 struct {
	state uint64
}

func (random *splitMix64) next() uint64 {
	random.state += 0x9e3779b97f4a7c15
	value := random.state
	value = (value ^ (value >> 30)) * 0xbf58476d1ce4e5b9
	value = (value ^ (value >> 27)) * 0x94d049bb133111eb
	return value ^ (value >> 31)
}

func maximumGeneratedTransfers(targetTxRate int, warmup time.Duration, duration time.Duration) (uint64, error) {
	if targetTxRate <= 0 || warmup < 0 || duration <= 0 || warmup%time.Second != 0 || duration%time.Second != 0 {
		return 0, fmt.Errorf("load window must use a positive rate and whole seconds")
	}
	warmupCount := uint64(warmupRate(targetTxRate)) * uint64(warmup/time.Second)
	activeCount := uint64(targetTxRate) * uint64(duration/time.Second)
	if math.MaxUint64-warmupCount <= activeCount {
		return 0, fmt.Errorf("load window generates too many transfers")
	}
	return warmupCount + activeCount + 1, nil
}

func DeriveProvisioning(cfg Config) ([]ProvisioningScenario, error) {
	transferCount, err := maximumGeneratedTransfers(cfg.TargetTxRate, cfg.Warmup, cfg.Duration)
	if err != nil {
		return nil, err
	}
	planner, err := newWorkloadPlanner(cfg.Scenarios)
	if err != nil {
		return nil, err
	}
	debitsByScenario := make([]map[string]int64, len(cfg.Scenarios))
	for index := range debitsByScenario {
		debitsByScenario[index] = make(map[string]int64)
	}
	for range transferCount {
		transfer := planner.Next()
		if transfer.ScenarioType != config.ScenarioHappyPath {
			continue
		}
		current := debitsByScenario[transfer.ScenarioIndex][transfer.Pair.Payer]
		if transfer.Amount > math.MaxInt64-current {
			return nil, fmt.Errorf("derived debit total overflows for scenario %q payer %s", transfer.ScenarioType, transfer.Pair.Payer)
		}
		debitsByScenario[transfer.ScenarioIndex][transfer.Pair.Payer] = current + transfer.Amount
	}

	provisioning := make([]ProvisioningScenario, len(cfg.Scenarios))
	for index, scenario := range cfg.Scenarios {
		entry := ProvisioningScenario{Type: scenario.Type, ResetIfExists: true}
		if scenario.Type == config.ScenarioHappyPath {
			var maximumDebit int64
			for _, debit := range debitsByScenario[index] {
				if debit > maximumDebit {
					maximumDebit = debit
				}
			}
			if maximumDebit > math.MaxInt64/settlementBucketCount {
				return nil, fmt.Errorf("derived payer funding overflows for scenario %q", scenario.Type)
			}
			requiredCents := maximumDebit * settlementBucketCount
			entry.PayerBalance = requiredCents / 100
			if requiredCents%100 != 0 {
				entry.PayerBalance++
			}
		}
		provisioning[index] = entry
	}
	return provisioning, nil
}
