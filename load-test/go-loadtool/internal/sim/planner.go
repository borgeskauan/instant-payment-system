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
	ScenarioName  string
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
	Name                 string
	PayerBalance         string
	ReceiverBalance      string
	ResetIfExists        bool
	payerBalanceCents    int64
	receiverBalanceCents int64
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
		participants := scenario.Participants
		amount := scenario.Amount
		if amount.Minimum <= 0 || amount.Maximum < amount.Minimum {
			return nil, fmt.Errorf("scenario %q requires a valid amount range", scenario.Name)
		}
		if participants.PairNumberStart <= 0 || participants.HotPairCount <= 0 || participants.ColdPairCount <= 0 || participants.HotTrafficShare <= 0 || participants.HotTrafficShare >= 1 {
			return nil, fmt.Errorf("scenario %q requires a valid participant range", scenario.Name)
		}
		quota := int(math.Round(scenario.Share * config.ScenarioSelectionBlockSize))
		if scenario.Share <= 0 || math.Abs(scenario.Share*config.ScenarioSelectionBlockSize-float64(quota)) > 1e-9 {
			return nil, fmt.Errorf("scenario %q share must select a whole number of entries in a %d-entry block", scenario.Name, config.ScenarioSelectionBlockSize)
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
	participants := scenario.Participants
	amountRange := scenario.Amount

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
		ScenarioName:  scenario.Name,
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

func maximumGeneratedTransfers(warmupRate int, warmup time.Duration, targetTxRate int, duration time.Duration) (uint64, error) {
	if warmupRate <= 0 || targetTxRate <= 0 || warmup <= 0 || duration <= 0 || warmup%time.Second != 0 || duration%time.Second != 0 {
		return 0, fmt.Errorf("load window must use a positive rate and whole seconds")
	}
	if warmupRate > math.MaxInt/4 || targetTxRate > math.MaxInt/4 {
		return 0, fmt.Errorf("load window rate is too large to size simulator queues safely")
	}
	warmupCount, ok := checkedUint64Product(uint64(warmupRate), uint64(warmup/time.Second))
	if !ok {
		return 0, fmt.Errorf("load window generates too many transfers")
	}
	activeCount, ok := checkedUint64Product(uint64(targetTxRate), uint64(duration/time.Second))
	if !ok {
		return 0, fmt.Errorf("load window generates too many transfers")
	}
	if math.MaxUint64-warmupCount <= activeCount {
		return 0, fmt.Errorf("load window generates too many transfers")
	}
	return warmupCount + activeCount, nil
}

func checkedUint64Product(left, right uint64) (uint64, bool) {
	if left != 0 && right > math.MaxUint64/left {
		return 0, false
	}
	return left * right, true
}

func DeriveProvisioning(cfg Config) ([]ProvisioningScenario, error) {
	transferCount, err := maximumGeneratedTransfers(cfg.Warmup.TargetTxRate, cfg.Warmup.Duration, cfg.TargetTxRate, cfg.Duration)
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
		if cfg.Scenarios[transfer.ScenarioIndex].Funding.Payer.Mode != config.FundingCoverGeneratedDebits {
			continue
		}
		current := debitsByScenario[transfer.ScenarioIndex][transfer.Pair.Payer]
		if transfer.Amount > math.MaxInt64-current {
			return nil, fmt.Errorf("derived debit total overflows for scenario %q payer %s", transfer.ScenarioName, transfer.Pair.Payer)
		}
		debitsByScenario[transfer.ScenarioIndex][transfer.Pair.Payer] = current + transfer.Amount
	}

	provisioning := make([]ProvisioningScenario, len(cfg.Scenarios))
	for index, scenario := range cfg.Scenarios {
		entry := ProvisioningScenario{
			Name:                 scenario.Name,
			PayerBalance:         scenario.Funding.Payer.Balance,
			ReceiverBalance:      scenario.Funding.Receiver.Balance,
			ResetIfExists:        scenario.Funding.ResetIfExists,
			payerBalanceCents:    scenario.Funding.Payer.BalanceCents,
			receiverBalanceCents: scenario.Funding.Receiver.BalanceCents,
		}
		switch scenario.Funding.Payer.Mode {
		case config.FundingCoverGeneratedDebits:
			var maximumDebit int64
			for _, debit := range debitsByScenario[index] {
				if debit > maximumDebit {
					maximumDebit = debit
				}
			}
			if maximumDebit > math.MaxInt64/settlementBucketCount {
				return nil, fmt.Errorf("derived payer funding overflows for scenario %q", scenario.Name)
			}
			requiredCents := maximumDebit * settlementBucketCount
			entry.PayerBalance = config.FormatBalance(requiredCents)
			entry.payerBalanceCents = requiredCents
		case config.FundingFixed:
			if entry.PayerBalance == "" {
				return nil, fmt.Errorf("scenario %q fixed payer funding requires a resolved balance", scenario.Name)
			}
		default:
			return nil, fmt.Errorf("scenario %q has unsupported payer funding mode %q", scenario.Name, scenario.Funding.Payer.Mode)
		}
		if scenario.Funding.Receiver.Mode != config.FundingFixed || entry.ReceiverBalance == "" {
			return nil, fmt.Errorf("scenario %q requires fixed receiver funding with a resolved balance", scenario.Name)
		}
		provisioning[index] = entry
	}
	return provisioning, nil
}
