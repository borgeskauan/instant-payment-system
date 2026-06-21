package report

import (
	"encoding/csv"
	"io"
	"os"
	"strconv"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/events"
)

type SystemStatSample struct {
	Timestamp       time.Time
	Source          string
	Name            string
	CPUPercent      float64
	CPULimitPercent float64
	MemUsedMB       float64
	MemLimitMB      float64
}

type resourceAccumulator struct {
	samples    int
	cpuTotal   float64
	cpuSamples int
	cpuLimit   float64
	memTotal   float64
	memSamples int
	memLimit   float64
}

func ReadSystemStats(path string) ([]SystemStatSample, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	reader := csv.NewReader(file)
	header, err := reader.Read()
	if err != nil {
		return nil, err
	}
	columns := make(map[string]int, len(header))
	for index, name := range header {
		columns[name] = index
	}

	var samples []SystemStatSample
	for {
		record, err := reader.Read()
		if err == io.EOF {
			return samples, nil
		}
		if err != nil {
			return nil, err
		}
		sample, err := parseSystemStat(record, columns)
		if err != nil {
			return nil, err
		}
		samples = append(samples, sample)
	}
}

func parseSystemStat(record []string, columns map[string]int) (SystemStatSample, error) {
	timestamp, err := time.Parse(time.RFC3339, columnValue(record, columns, "timestamp"))
	if err != nil {
		return SystemStatSample{}, err
	}
	return SystemStatSample{
		Timestamp:       timestamp,
		Source:          columnValue(record, columns, "source"),
		Name:            columnValue(record, columns, "name"),
		CPUPercent:      parseOptionalFloat(record, columns, "cpu_percent"),
		CPULimitPercent: parseOptionalFloat(record, columns, "cpu_limit_percent"),
		MemUsedMB:       parseOptionalFloat(record, columns, "mem_used_mb"),
		MemLimitMB:      parseOptionalFloat(record, columns, "mem_limit_mb"),
	}, nil
}

func columnValue(record []string, columns map[string]int, name string) string {
	index, ok := columns[name]
	if !ok || index >= len(record) {
		return ""
	}
	return record[index]
}

func parseOptionalFloat(record []string, columns map[string]int, name string) float64 {
	value := columnValue(record, columns, name)
	if value == "" {
		return 0
	}
	parsed, err := strconv.ParseFloat(value, 64)
	if err != nil {
		return 0
	}
	return parsed
}

func buildResourceSummary(starts []events.Start, options Options) *ResourceSummary {
	if len(starts) == 0 || len(options.SystemStats) == 0 {
		return nil
	}
	windowStart := firstStartedAt(starts) + options.Warmup.Nanoseconds()
	windowEnd := int64(0)
	if options.Duration > 0 {
		windowEnd = windowStart + options.Duration.Nanoseconds()
	}

	accumulators := make(map[string]*resourceAccumulator)
	for _, sample := range options.SystemStats {
		if sample.Source != "container" || sample.Name == "" {
			continue
		}
		sampleNS := sample.Timestamp.UnixNano()
		if sampleNS < windowStart {
			continue
		}
		if windowEnd > 0 && sampleNS >= windowEnd {
			continue
		}

		accumulator := accumulators[sample.Name]
		if accumulator == nil {
			accumulator = &resourceAccumulator{}
			accumulators[sample.Name] = accumulator
		}
		accumulator.samples++
		accumulator.cpuTotal += sample.CPUPercent
		accumulator.cpuSamples++
		if sample.CPULimitPercent > accumulator.cpuLimit {
			accumulator.cpuLimit = sample.CPULimitPercent
		}
		if sample.MemUsedMB > 0 {
			accumulator.memTotal += sample.MemUsedMB
			accumulator.memSamples++
		}
		if sample.MemLimitMB > accumulator.memLimit {
			accumulator.memLimit = sample.MemLimitMB
		}
	}
	if len(accumulators) == 0 {
		return nil
	}

	containers := make(map[string]ContainerResourceSummary, len(accumulators))
	for name, accumulator := range accumulators {
		container := ContainerResourceSummary{Samples: accumulator.samples}
		if accumulator.cpuSamples > 0 {
			avgCPU := accumulator.cpuTotal / float64(accumulator.cpuSamples)
			cpu := &CPUResourceSummary{AvgPercent: avgCPU}
			if accumulator.cpuLimit > 0 {
				limit := accumulator.cpuLimit
				cpu.LimitPercent = &limit
			}
			container.CPU = cpu
		}
		if accumulator.memSamples > 0 && accumulator.memLimit > 0 {
			avgMemory := accumulator.memTotal / float64(accumulator.memSamples)
			container.Memory = &MemoryResourceSummary{
				AvgMB:             avgMemory,
				LimitMB:           accumulator.memLimit,
				AvgOfLimitPercent: avgMemory / accumulator.memLimit * 100,
			}
		}
		containers[name] = container
	}

	return &ResourceSummary{Containers: containers}
}
