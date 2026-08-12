package sim

import (
	"fmt"
	"math"

	"instant-payment-system/load-test/go-loadtool/internal/config"
)

const (
	pacs008ReplayShuffleDomain uint64 = 0xd1b54a32d192ed03
	pacs002ReplayShuffleDomain uint64 = 0x94d049bb133111eb
)

type replaySelector struct {
	quota       int
	blockIndex  uint64
	block       []bool
	blockOffset int
	domain      uint64
}

func newReplaySelector(share float64) (*replaySelector, error) {
	return newReplaySelectorWithDomain(share, pacs008ReplayShuffleDomain)
}

func newReplaySelectorWithDomain(share float64, domain uint64) (*replaySelector, error) {
	quota := share * config.ScenarioSelectionBlockSize
	roundedQuota := math.Round(quota)
	if share <= 0 || share > 1 || math.Abs(quota-roundedQuota) > 1e-9 {
		return nil, fmt.Errorf("replay share must select a whole number of entries in a %d-entry block", config.ScenarioSelectionBlockSize)
	}
	return &replaySelector{quota: int(roundedQuota), domain: domain}, nil
}

func (selector *replaySelector) Next() bool {
	if selector.blockOffset >= len(selector.block) {
		selector.block = selector.shuffledBlock(selector.blockIndex)
		selector.blockIndex++
		selector.blockOffset = 0
	}
	selected := selector.block[selector.blockOffset]
	selector.blockOffset++
	return selected
}

func (selector *replaySelector) shuffledBlock(blockIndex uint64) []bool {
	block := make([]bool, config.ScenarioSelectionBlockSize)
	for index := range selector.quota {
		block[index] = true
	}
	random := splitMix64{state: ((blockIndex + 1) * 0x9e3779b97f4a7c15) ^ selector.domain}
	for index := len(block) - 1; index > 0; index-- {
		swapIndex := int(random.next() % uint64(index+1))
		block[index], block[swapIndex] = block[swapIndex], block[index]
	}
	return block
}
