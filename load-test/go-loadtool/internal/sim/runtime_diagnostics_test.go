package sim

import (
	"encoding/csv"
	"errors"
	"math"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"testing"
	"time"
)

type recordingActiveDiagnostics struct {
	order    *[]string
	startErr error
	stopErr  error
}

func (diagnostics recordingActiveDiagnostics) Start() error {
	*diagnostics.order = append(*diagnostics.order, "start")
	return diagnostics.startErr
}

func (diagnostics recordingActiveDiagnostics) Stop() error {
	*diagnostics.order = append(*diagnostics.order, "stop")
	return diagnostics.stopErr
}

func TestRunActiveWindowBoundsDiagnosticsAroundGeneration(t *testing.T) {
	var order []string
	diagnostics := recordingActiveDiagnostics{order: &order}

	err := runActiveWindow(diagnostics, func() error {
		order = append(order, "generate")
		return nil
	})

	if err != nil {
		t.Fatalf("runActiveWindow() error = %v", err)
	}
	if got := strings.Join(order, ","); got != "start,generate,stop" {
		t.Fatalf("active window order = %q, want start,generate,stop", got)
	}
}

func TestRunActiveWindowDoesNotGenerateWhenDiagnosticsCannotStart(t *testing.T) {
	wantErr := errors.New("profiling unavailable")
	var order []string
	diagnostics := recordingActiveDiagnostics{order: &order, startErr: wantErr}

	err := runActiveWindow(diagnostics, func() error {
		order = append(order, "generate")
		return nil
	})

	if !errors.Is(err, wantErr) {
		t.Fatalf("runActiveWindow() error = %v, want start error", err)
	}
	if got := strings.Join(order, ","); got != "start" {
		t.Fatalf("active window order = %q, want start", got)
	}
}

func TestRunActiveWindowStopsDiagnosticsWhenGenerationFails(t *testing.T) {
	wantErr := errors.New("generation failed")
	var order []string
	diagnostics := recordingActiveDiagnostics{order: &order}

	err := runActiveWindow(diagnostics, func() error {
		order = append(order, "generate")
		return wantErr
	})

	if !errors.Is(err, wantErr) {
		t.Fatalf("runActiveWindow() error = %v, want generation error", err)
	}
	if got := strings.Join(order, ","); got != "start,generate,stop" {
		t.Fatalf("active window order = %q, want start,generate,stop", got)
	}
}

func TestRuntimeDiagnosticsWritesActiveWindowProfilesAndSamples(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "diagnostics", "loadtool")
	diagnostics := newRuntimeDiagnostics(dir, 10*time.Millisecond)

	if err := diagnostics.Start(); err != nil {
		t.Fatalf("Start() error = %v", err)
	}
	exerciseRuntimeDiagnostics(80 * time.Millisecond)
	if err := diagnostics.Stop(); err != nil {
		t.Fatalf("Stop() error = %v", err)
	}

	for _, name := range []string{
		"cpu.pprof",
		"mutex.pprof",
		"allocs-before-active.pprof",
		"allocs-after-active.pprof",
	} {
		path := filepath.Join(dir, name)
		info, err := os.Stat(path)
		if err != nil {
			t.Fatalf("Stat(%s) error = %v", name, err)
		}
		if info.Size() == 0 {
			t.Fatalf("%s is empty", name)
		}
	}

	file, err := os.Open(filepath.Join(dir, "runtime.csv"))
	if err != nil {
		t.Fatalf("Open(runtime.csv) error = %v", err)
	}
	defer file.Close()
	records, err := csv.NewReader(file).ReadAll()
	if err != nil {
		t.Fatalf("ReadAll(runtime.csv) error = %v", err)
	}
	wantHeader := []string{
		"sampled_at_ns", "elapsed_ns", "goroutines", "heap_live_bytes", "heap_objects",
		"heap_allocated_bytes", "gc_cycles", "gc_cpu_delta_ns", "user_cpu_delta_ns",
		"mutex_wait_delta_ns", "gc_pause_count", "gc_pause_p99_ns", "gc_pause_max_ns",
		"scheduler_latency_count", "scheduler_latency_p50_ns", "scheduler_latency_p95_ns",
		"scheduler_latency_p99_ns", "scheduler_latency_max_ns",
	}
	if len(records) < 3 {
		t.Fatalf("runtime.csv records = %d, want header plus at least two samples", len(records))
	}
	if len(records[0]) != len(wantHeader) {
		t.Fatalf("runtime.csv header columns = %d, want %d: %v", len(records[0]), len(wantHeader), records[0])
	}
	for index, want := range wantHeader {
		if records[0][index] != want {
			t.Fatalf("runtime.csv header[%d] = %q, want %q", index, records[0][index], want)
		}
	}
	previousTimestamp := int64(0)
	for rowIndex, record := range records[1:] {
		if len(record) != len(wantHeader) {
			t.Fatalf("runtime.csv row %d columns = %d, want %d", rowIndex+1, len(record), len(wantHeader))
		}
		timestamp, err := strconv.ParseInt(record[0], 10, 64)
		if err != nil || timestamp <= previousTimestamp {
			t.Fatalf("runtime.csv row %d timestamp = %q after %d, error = %v", rowIndex+1, record[0], previousTimestamp, err)
		}
		previousTimestamp = timestamp
	}
}

func TestSummarizeHistogramDeltaUsesOnlyNewSamples(t *testing.T) {
	bounds := []float64{0, 0.001, 0.002, 0.005, math.Inf(1)}
	previous := []uint64{9, 4, 7, 2}
	current := []uint64{9, 6, 10, 2}

	summary := summarizeHistogramDelta(bounds, previous, current)

	if summary.Count != 5 {
		t.Fatalf("Count = %d, want 5", summary.Count)
	}
	if summary.P50 != 5*time.Millisecond || summary.P95 != 5*time.Millisecond || summary.P99 != 5*time.Millisecond || summary.Max != 5*time.Millisecond {
		t.Fatalf("summary = %#v, want all quantiles at 5ms", summary)
	}
}

func exerciseRuntimeDiagnostics(duration time.Duration) {
	deadline := time.Now().Add(duration)
	var value uint64
	var retained [][]byte
	for time.Now().Before(deadline) {
		for index := 0; index < 10_000; index++ {
			value += uint64(index*index + 1)
		}
		retained = append(retained, make([]byte, 4<<10))
		if len(retained) > 32 {
			retained = retained[:0]
		}
	}
	runtime.KeepAlive(value)
	runtime.KeepAlive(retained)
}
