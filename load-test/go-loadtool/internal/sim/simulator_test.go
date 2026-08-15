package sim

import (
	"context"
	"errors"
	"io"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"google.golang.org/grpc/connectivity"
	"instant-payment-system/load-test/go-loadtool/internal/config"
	"instant-payment-system/load-test/go-loadtool/internal/events"
	"instant-payment-system/load-test/go-loadtool/internal/gen/notificationpb"
	"instant-payment-system/load-test/go-loadtool/internal/ids"
	"instant-payment-system/load-test/go-loadtool/internal/payload"
)

func TestLoadRateUsesHalfTargetDuringWarmup(t *testing.T) {
	targetRate := 2000
	warmup := 30 * time.Second

	if got := loadRateForElapsed(0, warmup, targetRate); got != 1000 {
		t.Fatalf("loadRateForElapsed during warmup = %d, want 1000", got)
	}
	if got := loadRateForElapsed(29*time.Second, warmup, targetRate); got != 1000 {
		t.Fatalf("loadRateForElapsed before warmup end = %d, want 1000", got)
	}
	if got := loadRateForElapsed(30*time.Second, warmup, targetRate); got != 2000 {
		t.Fatalf("loadRateForElapsed after warmup = %d, want 2000", got)
	}
}

func TestLoadRateWarmupNeverDropsBelowOnePerSecond(t *testing.T) {
	if got := loadRateForElapsed(0, 30*time.Second, 1); got != 1 {
		t.Fatalf("loadRateForElapsed with target rate 1 = %d, want 1", got)
	}
}

func TestLoadRateUsesScheduledCursorWhenGeneratorIsBehind(t *testing.T) {
	start := time.Unix(100, 0)
	scheduledAt := start.Add(10 * time.Second)

	if got := loadRateForScheduledTime(scheduledAt, start, time.Minute, 2_000); got != 1_000 {
		t.Fatalf("load rate for delayed warmup cursor = %d, want 1000", got)
	}
}

func TestDelayedCursorDoesNotCreateAdditionalScheduledTransfers(t *testing.T) {
	start := time.Unix(100, 0)
	next := start.Add(time.Second)
	end := start.Add(4 * time.Second)
	generated := 0

	for next.Before(end) {
		rate := loadRateForScheduledTime(next, start, 2*time.Second, 4)
		next = next.Add(time.Second / time.Duration(rate))
		generated++
	}

	// The delayed cursor has two remaining warmup positions at 2/s followed
	// by eight active positions at 4/s. Applying the active rate
	// retroactively would incorrectly produce twelve positions.
	if generated != 10 {
		t.Fatalf("scheduled transfers after delay = %d, want 10", generated)
	}
}

func TestRunRejectsUnsafeRateBeforeCreatingOutput(t *testing.T) {
	outputDir := filepath.Join(t.TempDir(), "run")
	err := Run(Config{
		TargetTxRate: math.MaxInt/4 + 1,
		Duration:     time.Second,
		Scenarios:    mixedPlannerScenarios(),
		OutputDir:    outputDir,
	})
	if err == nil || !strings.Contains(err.Error(), "rate is too large") {
		t.Fatalf("Run error = %v, want rate is too large", err)
	}
	if _, statErr := os.Stat(outputDir); !os.IsNotExist(statErr) {
		t.Fatalf("output directory was created before rate validation: %v", statErr)
	}
}

func TestOriginalStartWindowIsSemiOpen(t *testing.T) {
	start := time.Unix(100, 0)
	end := start.Add(time.Second)
	if !canStartOriginal(start, end) {
		t.Fatal("generation start was excluded")
	}
	if !canStartOriginal(end.Add(-time.Nanosecond), end) {
		t.Fatal("instant before generation end was excluded")
	}
	if canStartOriginal(end, end) || canStartOriginal(end.Add(time.Nanosecond), end) {
		t.Fatal("generation end was not exclusive")
	}
}

func TestTransferJobUsesConfiguredScenarioAmountAndHotColdDistribution(t *testing.T) {
	s := &simulator{
		cfg: Config{
			Scenarios: []config.Scenario{{
				Name:  "renamed-workload",
				Share: 1,
				Participants: config.HotColdPairDistribution{
					PairNumberStart: 101,
					HotPairCount:    10,
					ColdPairCount:   40,
					HotTrafficShare: 0.8,
				},
				Amount: config.SequentialRangeAmount{
					Minimum: 100,
					Maximum: 102,
				},
			}},
		},
		runID: "test-run",
	}
	planner, err := newWorkloadPlanner(s.cfg.Scenarios)
	if err != nil {
		t.Fatal(err)
	}
	pairs := buildPairs(101, 50)
	if pairs[0] != ids.PSPPair(101) || pairs[len(pairs)-1] != ids.PSPPair(150) {
		t.Fatalf("pair range = %#v...%#v, want 101...150", pairs[0], pairs[len(pairs)-1])
	}
	hotPairs := make(map[string]bool)
	for _, pair := range pairs[:10] {
		hotPairs[pair.Payer] = true
	}

	hotCount := 0
	coldCount := 0
	for seq := uint64(0); seq < 100; seq++ {
		job := s.transferJobForSequence(seq, planner.Next())
		if job.ScenarioName != "renamed-workload" {
			t.Fatalf("sequence %d ScenarioName = %q", seq, job.ScenarioName)
		}
		wantAmount := int64(100 + seq%3)
		if job.Amount != wantAmount {
			t.Fatalf("sequence %d Amount = %d, want %d", seq, job.Amount, wantAmount)
		}
		if hotPairs[job.Pair.Payer] {
			hotCount++
		} else {
			coldCount++
		}
	}
	if hotCount != 80 || coldCount != 20 {
		t.Fatalf("hot/cold jobs = %d/%d, want 80/20", hotCount, coldCount)
	}
}

func TestStatusWorkersProcessQueuedJobsWithBoundedConcurrency(t *testing.T) {
	const workerCount = 4
	const jobCount = 50

	var processed atomic.Int64
	var active atomic.Int64
	var maxActive atomic.Int64
	s := &simulator{
		sendPacs002Func: func(context.Context, statusJob) {
			current := active.Add(1)
			for {
				previous := maxActive.Load()
				if current <= previous || maxActive.CompareAndSwap(previous, current) {
					break
				}
			}
			time.Sleep(time.Millisecond)
			active.Add(-1)
			processed.Add(1)
		},
	}

	jobs := make(chan statusJob, jobCount)
	var workers sync.WaitGroup
	s.startStatusWorkers(context.Background(), &workers, jobs, workerCount)
	for i := 0; i < jobCount; i++ {
		jobs <- statusJob{receiverISPB: "20000001", endToEndID: "E2E"}
	}
	close(jobs)
	workers.Wait()

	if got := processed.Load(); got != jobCount {
		t.Fatalf("processed status jobs = %d, want %d", got, jobCount)
	}
	if got := maxActive.Load(); got > workerCount {
		t.Fatalf("max concurrent status workers = %d, want <= %d", got, workerCount)
	}
}

func TestRepeatedPacs008NotificationQueuesOneOriginalPacs002(t *testing.T) {
	s := &simulator{
		statusJobs:      make(chan statusJob, 2),
		statusQueuedIDs: make(map[string]struct{}),
		transferScenarios: map[string]string{
			"tx-1": "happy-path",
		},
	}

	s.enqueuePacs002(context.Background(), "20000001", "tx-1")
	s.enqueuePacs002(context.Background(), "20000001", "tx-1")

	if got := len(s.statusJobs); got != 1 {
		t.Fatalf("queued PACS.002 originals = %d, want 1", got)
	}
	job := <-s.statusJobs
	if job.scenarioName != "happy-path" {
		t.Fatalf("scenario name = %q", job.scenarioName)
	}
}

func TestClientCertPathsUsesIspbDirectory(t *testing.T) {
	certPath, keyPath := clientCertPaths("/tmp/loadtool-certs", "20000001")

	if certPath != "/tmp/loadtool-certs/psp-20000001/client.crt" {
		t.Fatalf("certPath = %q", certPath)
	}
	if keyPath != "/tmp/loadtool-certs/psp-20000001/client.key" {
		t.Fatalf("keyPath = %q", keyPath)
	}
}

func TestNewHTTPClientsRejectsPlaintextBaseURL(t *testing.T) {
	_, err := newHTTPClients(Config{BaseURL: "http://localhost:8001"}, nil)

	if err == nil || !strings.Contains(err.Error(), "must use https") {
		t.Fatalf("newHTTPClients error = %v, want HTTPS validation error", err)
	}
}

func TestPostUsesClientForAuthenticatedIspb(t *testing.T) {
	var payerCalls atomic.Int64
	var receiverCalls atomic.Int64
	s := &simulator{
		httpClients: map[string]*http.Client{
			"10000001": {
				Transport: roundTripperFunc(func(*http.Request) (*http.Response, error) {
					payerCalls.Add(1)
					return &http.Response{StatusCode: http.StatusOK, Body: http.NoBody}, nil
				}),
			},
			"20000001": {
				Transport: roundTripperFunc(func(*http.Request) (*http.Response, error) {
					receiverCalls.Add(1)
					return &http.Response{StatusCode: http.StatusAccepted, Body: http.NoBody}, nil
				}),
			},
		},
	}

	payerStatus := s.post(
		context.Background(),
		"10000001",
		"https://localhost:8001/transfer",
		[]byte("pacs008"),
	).HTTPStatus
	receiverStatus := s.post(
		context.Background(),
		"20000001",
		"https://localhost:8001/transfer/status",
		[]byte("pacs002"),
	).HTTPStatus

	if payerStatus != http.StatusOK || payerCalls.Load() != 1 {
		t.Fatalf("payer status/calls = %d/%d", payerStatus, payerCalls.Load())
	}
	if receiverStatus != http.StatusAccepted || receiverCalls.Load() != 1 {
		t.Fatalf("receiver status/calls = %d/%d", receiverStatus, receiverCalls.Load())
	}
}

func TestNotificationStreamDoesNotSubscribeAndAcksDelivery(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	eventsPath := filepath.Join(t.TempDir(), "events.csv")
	writer, err := events.NewNotificationWriter(eventsPath)
	if err != nil {
		t.Fatal(err)
	}

	stream := newFakeNotificationStream()
	s := &simulator{eventWriter: writer}
	var wg sync.WaitGroup
	wg.Add(1)
	go s.consumeNotificationStream(ctx, &wg, notificationStreamSession{
		ispb:         "20000001",
		receiverRole: false,
		stream:       stream,
	})

	select {
	case msg := <-stream.sent:
		t.Fatalf("unexpected client message before notification: %#v", msg)
	case <-time.After(20 * time.Millisecond):
	}

	stream.received <- &notificationpb.Notification{
		DeliveryId: "delivery-1",
		Payload:    payload.Pacs002("tx-1"),
	}

	select {
	case msg := <-stream.sent:
		if msg.GetAck() == nil {
			t.Fatalf("client message has no ack: %#v", msg)
		}
		if got := msg.GetAck().GetDeliveryId(); got != "delivery-1" {
			t.Fatalf("ack delivery id = %q", got)
		}
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for ack")
	}

	cancel()
	close(stream.received)
	wg.Wait()
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	rows, err := events.ReadNotifications(eventsPath)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || rows[0].EndToEndID != "tx-1" || rows[0].StatusCode != "ACSP" || len(rows[0].ReasonCodes) != 0 {
		t.Fatalf("notification rows = %#v", rows)
	}
}

func TestOpenNotificationStreamsClosesAlreadyOpenedSessionsOnFailure(t *testing.T) {
	var closed atomic.Int64
	openCount := 0
	s := &simulator{
		openNotificationStreamFunc: func(context.Context, string) (notificationStreamClient, func() error, error) {
			openCount++
			if openCount == 1 {
				return newFakeNotificationStream(), func() error {
					closed.Add(1)
					return nil
				}, nil
			}
			return nil, nil, errors.New("handshake failed")
		},
	}

	_, err := s.openNotificationStreams(context.Background(), []ids.Pair{
		{Payer: "10000001", Receiver: "20000001"},
	})

	if err == nil {
		t.Fatal("expected error")
	}
	if got := closed.Load(); got != 1 {
		t.Fatalf("closed sessions = %d, want 1", got)
	}
}

func TestWaitForGrpcReadyConnectsAndWaitsUntilReady(t *testing.T) {
	conn := &fakeGrpcReadyConn{
		states: []connectivity.State{
			connectivity.Idle,
			connectivity.Connecting,
			connectivity.Ready,
		},
	}

	if err := waitForGrpcReady(context.Background(), conn); err != nil {
		t.Fatal(err)
	}

	if conn.connectCalls != 1 {
		t.Fatalf("connectCalls = %d, want 1", conn.connectCalls)
	}
	if len(conn.waitedStates) != 2 {
		t.Fatalf("waited states = %v, want two states", conn.waitedStates)
	}
	if conn.waitedStates[0] != connectivity.Idle {
		t.Fatalf("first waited state = %s", conn.waitedStates[0])
	}
	if conn.waitedStates[1] != connectivity.Connecting {
		t.Fatalf("second waited state = %s", conn.waitedStates[1])
	}
}

type fakeNotificationStream struct {
	received chan *notificationpb.Notification
	sent     chan *notificationpb.ClientMessage
}

func newFakeNotificationStream() *fakeNotificationStream {
	return &fakeNotificationStream{
		received: make(chan *notificationpb.Notification, 1),
		sent:     make(chan *notificationpb.ClientMessage, 1),
	}
}

func (f *fakeNotificationStream) Send(message *notificationpb.ClientMessage) error {
	f.sent <- message
	return nil
}

func (f *fakeNotificationStream) Recv() (*notificationpb.Notification, error) {
	message, ok := <-f.received
	if !ok {
		return nil, io.EOF
	}
	return message, nil
}

func (f *fakeNotificationStream) CloseSend() error {
	return nil
}

type fakeGrpcReadyConn struct {
	states       []connectivity.State
	index        int
	connectCalls int
	waitedStates []connectivity.State
}

type roundTripperFunc func(*http.Request) (*http.Response, error)

func (f roundTripperFunc) RoundTrip(request *http.Request) (*http.Response, error) {
	return f(request)
}

func (f *fakeGrpcReadyConn) Connect() {
	f.connectCalls++
}

func (f *fakeGrpcReadyConn) GetState() connectivity.State {
	return f.states[f.index]
}

func (f *fakeGrpcReadyConn) WaitForStateChange(_ context.Context, source connectivity.State) bool {
	f.waitedStates = append(f.waitedStates, source)
	if f.index >= len(f.states)-1 {
		return false
	}
	f.index++
	return true
}
