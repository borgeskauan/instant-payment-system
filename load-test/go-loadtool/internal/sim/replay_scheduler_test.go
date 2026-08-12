package sim

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/config"
	"instant-payment-system/load-test/go-loadtool/internal/events"
	"instant-payment-system/load-test/go-loadtool/internal/ids"
)

func TestReplaySchedulerDoesNotReleaseBeforeConfiguredDeadline(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	scheduler := newReplayScheduler(ctx, 1)
	delay := 60 * time.Millisecond
	startedAt := time.Now()
	if err := scheduler.Schedule(replayJob{dueAt: startedAt.Add(delay)}); err != nil {
		t.Fatal(err)
	}

	select {
	case <-scheduler.Ready():
		t.Fatal("replay was released before its configured delay")
	case <-time.After(delay / 2):
	}

	select {
	case <-scheduler.Ready():
		if elapsed := time.Since(startedAt); elapsed < delay {
			t.Fatalf("replay released after %s, want at least %s", elapsed, delay)
		}
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for scheduled replay")
	}
	scheduler.Close()
	scheduler.Wait()
}

func TestSelectedReplayIsScheduledBeforeOriginalCompletesAndReusesExactBody(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	dir := t.TempDir()
	startWriter, err := events.NewStartWriter(filepath.Join(dir, "starts.csv"))
	if err != nil {
		t.Fatal(err)
	}
	replayWriter, err := events.NewReplayWriter(filepath.Join(dir, "replays.csv"))
	if err != nil {
		t.Fatal(err)
	}

	delay := 70 * time.Millisecond
	scheduler := newReplayScheduler(ctx, 1)
	var replayWorkers sync.WaitGroup
	var buildCalls atomic.Int64
	var callCount atomic.Int64
	var bodiesMu sync.Mutex
	var bodies [][]byte
	originalRelease := make(chan struct{})
	replaySent := make(chan time.Time, 1)
	httpClient := &http.Client{Transport: roundTripperFunc(func(request *http.Request) (*http.Response, error) {
		body, readErr := io.ReadAll(request.Body)
		if readErr != nil {
			return nil, readErr
		}
		bodiesMu.Lock()
		bodies = append(bodies, body)
		bodiesMu.Unlock()
		call := callCount.Add(1)
		if call == 1 {
			<-originalRelease
			return &http.Response{StatusCode: http.StatusInternalServerError, Body: http.NoBody}, nil
		}
		replaySent <- time.Now()
		return &http.Response{StatusCode: http.StatusAccepted, Body: http.NoBody}, nil
	})}
	s := &simulator{
		cfg: Config{
			BaseURL: "https://localhost:8001",
			Replay: config.Replay{Pacs008: &config.Pacs008Replay{
				Share: 0.10,
				Delay: delay,
			}},
		},
		httpClients:     map[string]*http.Client{"10000001": httpClient},
		startWriter:     startWriter,
		replayWriter:    replayWriter,
		replayScheduler: scheduler,
		buildPacs008Func: func(string, string, string, int64) []byte {
			buildCalls.Add(1)
			return []byte("exact-pacs008-body")
		},
	}
	s.startReplayWorkers(ctx, &replayWorkers, scheduler.Ready(), 1)

	job := transferJob{
		ID:             "tx-1",
		Pair:           ids.Pair{Payer: "10000001", Receiver: "20000001"},
		Created:        time.Now().UnixNano(),
		Amount:         100,
		ScenarioName:   "happy-path",
		ReplaySelected: true,
	}
	originalStartedAt := time.Now()
	originalDone := make(chan struct{})
	go func() {
		s.sendPacs008(ctx, job)
		close(originalDone)
	}()

	select {
	case replayStartedAt := <-replaySent:
		if elapsed := replayStartedAt.Sub(originalStartedAt); elapsed < delay {
			t.Fatalf("replay started after %s, want at least %s", elapsed, delay)
		}
	case <-time.After(time.Second):
		t.Fatal("replay was not sent while original request remained in progress")
	}
	close(originalRelease)
	<-originalDone
	scheduler.Close()
	scheduler.Wait()
	replayWorkers.Wait()
	if err := startWriter.Close(); err != nil {
		t.Fatal(err)
	}
	if err := replayWriter.Close(); err != nil {
		t.Fatal(err)
	}

	if buildCalls.Load() != 1 {
		t.Fatalf("payload build calls = %d, want 1", buildCalls.Load())
	}
	bodiesMu.Lock()
	defer bodiesMu.Unlock()
	if len(bodies) != 2 || !bytes.Equal(bodies[0], bodies[1]) {
		t.Fatalf("POST bodies differ: %q", bodies)
	}
	starts, err := events.ReadStarts(filepath.Join(dir, "starts.csv"))
	if err != nil {
		t.Fatal(err)
	}
	if len(starts) != 1 || starts[0].HTTPStatus != http.StatusInternalServerError || !starts[0].Pacs008ReplaySelected {
		t.Fatalf("starts = %#v", starts)
	}
	replays, err := events.ReadReplays(filepath.Join(dir, "replays.csv"))
	if err != nil {
		t.Fatal(err)
	}
	if len(replays) != 1 || replays[0].HTTPStatus != http.StatusAccepted {
		t.Fatalf("replays = %#v", replays)
	}
}

func TestSelectedPacs002ReplayIsScheduledFromOriginalStartAndReusesExactBody(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	dir := t.TempDir()
	statusWriter, err := events.NewStatusStartWriter(filepath.Join(dir, "status-starts.csv"))
	if err != nil {
		t.Fatal(err)
	}
	replayWriter, err := events.NewReplayWriter(filepath.Join(dir, "replays.csv"))
	if err != nil {
		t.Fatal(err)
	}

	delay := 70 * time.Millisecond
	selector, err := newReplaySelectorWithDomain(1, pacs002ReplayShuffleDomain)
	if err != nil {
		t.Fatal(err)
	}
	scheduler := newReplayScheduler(ctx, 1)
	var replayWorkers sync.WaitGroup
	var buildCalls atomic.Int64
	var callCount atomic.Int64
	var bodiesMu sync.Mutex
	var bodies [][]byte
	originalRelease := make(chan struct{})
	replaySent := make(chan time.Time, 1)
	httpClient := &http.Client{Transport: roundTripperFunc(func(request *http.Request) (*http.Response, error) {
		body, readErr := io.ReadAll(request.Body)
		if readErr != nil {
			return nil, readErr
		}
		bodiesMu.Lock()
		bodies = append(bodies, body)
		bodiesMu.Unlock()
		if callCount.Add(1) == 1 {
			<-originalRelease
			return &http.Response{StatusCode: http.StatusInternalServerError, Body: http.NoBody}, nil
		}
		replaySent <- time.Now()
		return &http.Response{StatusCode: http.StatusAccepted, Body: http.NoBody}, nil
	})}
	s := &simulator{
		cfg: Config{
			BaseURL: "https://localhost:8001",
			Replay: config.Replay{Pacs002: &config.Pacs002Replay{
				Share: 1,
				Delay: delay,
			}},
		},
		httpClients:           map[string]*http.Client{"20000001": httpClient},
		statusStartWriter:     statusWriter,
		replayWriter:          replayWriter,
		replayScheduler:       scheduler,
		pacs002ReplaySelector: selector,
		buildPacs002Func: func(string) []byte {
			buildCalls.Add(1)
			return []byte("exact-pacs002-body")
		},
	}
	s.startReplayWorkers(ctx, &replayWorkers, scheduler.Ready(), 1)

	originalStartedAt := time.Now()
	originalDone := make(chan struct{})
	go func() {
		s.sendPacs002(ctx, statusJob{receiverISPB: "20000001", endToEndID: "tx-1", scenarioName: "happy-path"})
		close(originalDone)
	}()

	select {
	case replayStartedAt := <-replaySent:
		if elapsed := replayStartedAt.Sub(originalStartedAt); elapsed < delay {
			t.Fatalf("PACS.002 replay started after %s, want at least %s", elapsed, delay)
		}
	case <-time.After(time.Second):
		t.Fatal("PACS.002 replay was not sent while original request remained in progress")
	}
	close(originalRelease)
	<-originalDone
	scheduler.Close()
	scheduler.Wait()
	replayWorkers.Wait()
	if err := statusWriter.Close(); err != nil {
		t.Fatal(err)
	}
	if err := replayWriter.Close(); err != nil {
		t.Fatal(err)
	}

	if buildCalls.Load() != 1 {
		t.Fatalf("payload build calls = %d, want 1", buildCalls.Load())
	}
	bodiesMu.Lock()
	if len(bodies) != 2 || !bytes.Equal(bodies[0], bodies[1]) {
		t.Fatalf("POST bodies differ: %q", bodies)
	}
	bodiesMu.Unlock()
	statuses, err := events.ReadStatusStarts(filepath.Join(dir, "status-starts.csv"))
	if err != nil {
		t.Fatal(err)
	}
	if len(statuses) != 1 || statuses[0].HTTPStatus != http.StatusInternalServerError || !statuses[0].Pacs002ReplaySelected {
		t.Fatalf("status starts = %#v", statuses)
	}
	replays, err := events.ReadReplays(filepath.Join(dir, "replays.csv"))
	if err != nil {
		t.Fatal(err)
	}
	if len(replays) != 1 || replays[0].MessageType != events.MessagePacs002 || replays[0].SenderISPB != "20000001" || replays[0].HTTPStatus != http.StatusAccepted {
		t.Fatalf("replays = %#v", replays)
	}
}
