package sim

import (
	"encoding/csv"
	"errors"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"runtime"
	"runtime/metrics"
	"runtime/pprof"
	"strconv"
	"time"
)

const (
	runtimeDiagnosticsSampleInterval = 100 * time.Millisecond
	runtimeDiagnosticsFlushSamples   = 10
	runtimeDiagnosticsMutexFraction  = 10
)

var runtimeDiagnosticsHeader = []string{
	"sampled_at_ns",
	"elapsed_ns",
	"goroutines",
	"heap_live_bytes",
	"heap_objects",
	"heap_allocated_bytes",
	"gc_cycles",
	"gc_cpu_delta_ns",
	"user_cpu_delta_ns",
	"mutex_wait_delta_ns",
	"gc_pause_count",
	"gc_pause_p99_ns",
	"gc_pause_max_ns",
	"scheduler_latency_count",
	"scheduler_latency_p50_ns",
	"scheduler_latency_p95_ns",
	"scheduler_latency_p99_ns",
	"scheduler_latency_max_ns",
}

type histogramSnapshot struct {
	bounds []float64
	counts []uint64
}

type runtimeMetricSnapshot struct {
	goroutines         uint64
	heapLiveBytes      uint64
	heapObjects        uint64
	heapAllocatedBytes uint64
	gcCycles           uint64
	gcCPUSeconds       float64
	userCPUSeconds     float64
	mutexWaitSeconds   float64
	gcPauses           histogramSnapshot
	schedulerLatencies histogramSnapshot
}

type histogramSummary struct {
	Count uint64
	P50   time.Duration
	P95   time.Duration
	P99   time.Duration
	Max   time.Duration
}

type activeWindowDiagnostics interface {
	Start() error
	Stop() error
}

type runtimeDiagnostics struct {
	dir                   string
	interval              time.Duration
	startedAt             time.Time
	previous              runtimeMetricSnapshot
	runtimeFile           *os.File
	runtimeWriter         *csv.Writer
	cpuFile               *os.File
	stop                  chan struct{}
	done                  chan error
	previousMutexFraction int
	started               bool
	stopped               bool
}

func newRuntimeDiagnostics(dir string, interval time.Duration) *runtimeDiagnostics {
	if interval <= 0 {
		interval = runtimeDiagnosticsSampleInterval
	}
	return &runtimeDiagnostics{dir: dir, interval: interval}
}

func runActiveWindow(diagnostics activeWindowDiagnostics, generate func() error) error {
	if diagnostics == nil {
		return generate()
	}
	if err := diagnostics.Start(); err != nil {
		return fmt.Errorf("start Go runtime diagnostics: %w", err)
	}
	generationErr := generate()
	stopErr := diagnostics.Stop()
	return errors.Join(
		generationErr,
		wrapRuntimeDiagnosticsError("stop Go runtime diagnostics", stopErr),
	)
}

func (diagnostics *runtimeDiagnostics) Start() (startErr error) {
	if diagnostics.started {
		return fmt.Errorf("runtime diagnostics already started")
	}
	if diagnostics.dir == "" {
		return fmt.Errorf("runtime diagnostics directory is required")
	}
	if err := os.MkdirAll(diagnostics.dir, 0o755); err != nil {
		return fmt.Errorf("create runtime diagnostics directory: %w", err)
	}
	if err := diagnostics.writeProfile("allocs", "allocs-before-active.pprof"); err != nil {
		return err
	}

	diagnostics.runtimeFile, startErr = os.Create(filepath.Join(diagnostics.dir, "runtime.csv"))
	if startErr != nil {
		return fmt.Errorf("create runtime diagnostics samples: %w", startErr)
	}
	defer func() {
		if startErr != nil {
			_ = diagnostics.runtimeFile.Close()
		}
	}()
	diagnostics.runtimeWriter = csv.NewWriter(diagnostics.runtimeFile)
	if err := diagnostics.runtimeWriter.Write(runtimeDiagnosticsHeader); err != nil {
		return fmt.Errorf("write runtime diagnostics header: %w", err)
	}

	diagnostics.cpuFile, startErr = os.Create(filepath.Join(diagnostics.dir, "cpu.pprof"))
	if startErr != nil {
		return fmt.Errorf("create CPU profile: %w", startErr)
	}
	defer func() {
		if startErr != nil {
			_ = diagnostics.cpuFile.Close()
		}
	}()
	if err := pprof.StartCPUProfile(diagnostics.cpuFile); err != nil {
		return fmt.Errorf("start CPU profile: %w", err)
	}

	diagnostics.previousMutexFraction = runtime.SetMutexProfileFraction(runtimeDiagnosticsMutexFraction)
	diagnostics.startedAt = time.Now()
	diagnostics.previous = readRuntimeMetricSnapshot()
	if err := diagnostics.writeSample(diagnostics.startedAt, diagnostics.previous); err != nil {
		pprof.StopCPUProfile()
		runtime.SetMutexProfileFraction(diagnostics.previousMutexFraction)
		return err
	}
	diagnostics.runtimeWriter.Flush()
	if err := diagnostics.runtimeWriter.Error(); err != nil {
		pprof.StopCPUProfile()
		runtime.SetMutexProfileFraction(diagnostics.previousMutexFraction)
		return fmt.Errorf("flush initial runtime diagnostics sample: %w", err)
	}

	diagnostics.stop = make(chan struct{})
	diagnostics.done = make(chan error, 1)
	diagnostics.started = true
	go diagnostics.sampleRuntime()
	return nil
}

func (diagnostics *runtimeDiagnostics) Stop() error {
	if !diagnostics.started {
		return fmt.Errorf("runtime diagnostics are not started")
	}
	if diagnostics.stopped {
		return fmt.Errorf("runtime diagnostics already stopped")
	}
	diagnostics.stopped = true
	close(diagnostics.stop)
	sampleErr := <-diagnostics.done

	pprof.StopCPUProfile()
	runtime.SetMutexProfileFraction(diagnostics.previousMutexFraction)
	cpuCloseErr := diagnostics.cpuFile.Close()

	mutexErr := diagnostics.writeProfile("mutex", "mutex.pprof")
	allocsErr := diagnostics.writeProfile("allocs", "allocs-after-active.pprof")

	diagnostics.runtimeWriter.Flush()
	runtimeWriterErr := diagnostics.runtimeWriter.Error()
	runtimeCloseErr := diagnostics.runtimeFile.Close()
	return errors.Join(
		sampleErr,
		wrapRuntimeDiagnosticsError("close CPU profile", cpuCloseErr),
		mutexErr,
		allocsErr,
		wrapRuntimeDiagnosticsError("flush runtime diagnostics samples", runtimeWriterErr),
		wrapRuntimeDiagnosticsError("close runtime diagnostics samples", runtimeCloseErr),
	)
}

func (diagnostics *runtimeDiagnostics) sampleRuntime() {
	ticker := time.NewTicker(diagnostics.interval)
	defer ticker.Stop()
	samplesSinceFlush := 0
	for {
		select {
		case <-ticker.C:
			sampledAt := time.Now()
			current := readRuntimeMetricSnapshot()
			if err := diagnostics.writeSample(sampledAt, current); err != nil {
				diagnostics.done <- err
				return
			}
			diagnostics.previous = current
			samplesSinceFlush++
			if samplesSinceFlush == runtimeDiagnosticsFlushSamples {
				diagnostics.runtimeWriter.Flush()
				if err := diagnostics.runtimeWriter.Error(); err != nil {
					diagnostics.done <- fmt.Errorf("flush runtime diagnostics samples: %w", err)
					return
				}
				samplesSinceFlush = 0
			}
		case <-diagnostics.stop:
			current := readRuntimeMetricSnapshot()
			if err := diagnostics.writeSample(time.Now(), current); err != nil {
				diagnostics.done <- err
				return
			}
			diagnostics.done <- nil
			return
		}
	}
}

func (diagnostics *runtimeDiagnostics) writeSample(sampledAt time.Time, current runtimeMetricSnapshot) error {
	gcPauses := summarizeHistogramDelta(
		current.gcPauses.bounds,
		diagnostics.previous.gcPauses.counts,
		current.gcPauses.counts,
	)
	schedulerLatencies := summarizeHistogramDelta(
		current.schedulerLatencies.bounds,
		diagnostics.previous.schedulerLatencies.counts,
		current.schedulerLatencies.counts,
	)
	record := []string{
		strconv.FormatInt(sampledAt.UnixNano(), 10),
		strconv.FormatInt(sampledAt.Sub(diagnostics.startedAt).Nanoseconds(), 10),
		strconv.FormatUint(current.goroutines, 10),
		strconv.FormatUint(current.heapLiveBytes, 10),
		strconv.FormatUint(current.heapObjects, 10),
		strconv.FormatUint(current.heapAllocatedBytes, 10),
		strconv.FormatUint(current.gcCycles, 10),
		strconv.FormatInt(secondsDeltaNanoseconds(diagnostics.previous.gcCPUSeconds, current.gcCPUSeconds), 10),
		strconv.FormatInt(secondsDeltaNanoseconds(diagnostics.previous.userCPUSeconds, current.userCPUSeconds), 10),
		strconv.FormatInt(secondsDeltaNanoseconds(diagnostics.previous.mutexWaitSeconds, current.mutexWaitSeconds), 10),
		strconv.FormatUint(gcPauses.Count, 10),
		strconv.FormatInt(gcPauses.P99.Nanoseconds(), 10),
		strconv.FormatInt(gcPauses.Max.Nanoseconds(), 10),
		strconv.FormatUint(schedulerLatencies.Count, 10),
		strconv.FormatInt(schedulerLatencies.P50.Nanoseconds(), 10),
		strconv.FormatInt(schedulerLatencies.P95.Nanoseconds(), 10),
		strconv.FormatInt(schedulerLatencies.P99.Nanoseconds(), 10),
		strconv.FormatInt(schedulerLatencies.Max.Nanoseconds(), 10),
	}
	if err := diagnostics.runtimeWriter.Write(record); err != nil {
		return fmt.Errorf("write runtime diagnostics sample: %w", err)
	}
	return nil
}

func (diagnostics *runtimeDiagnostics) writeProfile(profileName, fileName string) error {
	profile := pprof.Lookup(profileName)
	if profile == nil {
		return fmt.Errorf("Go runtime profile %q is unavailable", profileName)
	}
	file, err := os.Create(filepath.Join(diagnostics.dir, fileName))
	if err != nil {
		return fmt.Errorf("create %s profile: %w", profileName, err)
	}
	writeErr := profile.WriteTo(file, 0)
	closeErr := file.Close()
	return errors.Join(
		wrapRuntimeDiagnosticsError("write "+profileName+" profile", writeErr),
		wrapRuntimeDiagnosticsError("close "+profileName+" profile", closeErr),
	)
}

func readRuntimeMetricSnapshot() runtimeMetricSnapshot {
	samples := []metrics.Sample{
		{Name: "/sched/goroutines:goroutines"},
		{Name: "/gc/heap/live:bytes"},
		{Name: "/gc/heap/objects:objects"},
		{Name: "/gc/heap/allocs:bytes"},
		{Name: "/gc/cycles/total:gc-cycles"},
		{Name: "/cpu/classes/gc/total:cpu-seconds"},
		{Name: "/cpu/classes/user:cpu-seconds"},
		{Name: "/sync/mutex/wait/total:seconds"},
		{Name: "/sched/pauses/total/gc:seconds"},
		{Name: "/sched/latencies:seconds"},
	}
	metrics.Read(samples)
	return runtimeMetricSnapshot{
		goroutines:         samples[0].Value.Uint64(),
		heapLiveBytes:      samples[1].Value.Uint64(),
		heapObjects:        samples[2].Value.Uint64(),
		heapAllocatedBytes: samples[3].Value.Uint64(),
		gcCycles:           samples[4].Value.Uint64(),
		gcCPUSeconds:       samples[5].Value.Float64(),
		userCPUSeconds:     samples[6].Value.Float64(),
		mutexWaitSeconds:   samples[7].Value.Float64(),
		gcPauses:           cloneHistogram(samples[8].Value.Float64Histogram()),
		schedulerLatencies: cloneHistogram(samples[9].Value.Float64Histogram()),
	}
}

func cloneHistogram(histogram *metrics.Float64Histogram) histogramSnapshot {
	return histogramSnapshot{
		bounds: append([]float64(nil), histogram.Buckets...),
		counts: append([]uint64(nil), histogram.Counts...),
	}
}

func summarizeHistogramDelta(bounds []float64, previous, current []uint64) histogramSummary {
	if len(bounds) != len(current)+1 || len(previous) != len(current) {
		return histogramSummary{}
	}
	delta := make([]uint64, len(current))
	var total uint64
	lastNonEmpty := -1
	for index := range current {
		if current[index] > previous[index] {
			delta[index] = current[index] - previous[index]
			total += delta[index]
			lastNonEmpty = index
		}
	}
	if total == 0 {
		return histogramSummary{}
	}
	return histogramSummary{
		Count: total,
		P50:   histogramQuantile(bounds, delta, total, 0.50),
		P95:   histogramQuantile(bounds, delta, total, 0.95),
		P99:   histogramQuantile(bounds, delta, total, 0.99),
		Max:   histogramBucketDuration(bounds, lastNonEmpty),
	}
}

func histogramQuantile(bounds []float64, counts []uint64, total uint64, quantile float64) time.Duration {
	rank := uint64(math.Ceil(float64(total) * quantile))
	if rank == 0 {
		rank = 1
	}
	var cumulative uint64
	for index, count := range counts {
		cumulative += count
		if cumulative >= rank {
			return histogramBucketDuration(bounds, index)
		}
	}
	return 0
}

func histogramBucketDuration(bounds []float64, index int) time.Duration {
	if index < 0 || index+1 >= len(bounds) {
		return 0
	}
	seconds := bounds[index+1]
	if math.IsInf(seconds, 1) {
		seconds = bounds[index]
	}
	if seconds <= 0 || math.IsInf(seconds, -1) {
		return 0
	}
	return time.Duration(math.Ceil(seconds * float64(time.Second)))
}

func secondsDeltaNanoseconds(previous, current float64) int64 {
	if current <= previous {
		return 0
	}
	return int64(math.Round((current - previous) * float64(time.Second)))
}

func wrapRuntimeDiagnosticsError(action string, err error) error {
	if err == nil {
		return nil
	}
	return fmt.Errorf("%s: %w", action, err)
}
