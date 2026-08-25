package sim

import (
	"context"
	"errors"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/connectivity"
	"instant-payment-system/load-test/go-loadtool/internal/config"
	"instant-payment-system/load-test/go-loadtool/internal/events"
	"instant-payment-system/load-test/go-loadtool/internal/gen/notificationpb"
	"instant-payment-system/load-test/go-loadtool/internal/ids"
	"instant-payment-system/load-test/go-loadtool/internal/payload"
	"instant-payment-system/load-test/go-loadtool/internal/pullmetrics"
)

func TestRunRejectsUnsafeRateBeforeCreatingOutput(t *testing.T) {
	outputDir := filepath.Join(t.TempDir(), "run")
	err := Run(Config{
		OfferedTxRate: math.MaxInt/4 + 1,
		Warmup: config.Warmup{
			Bootstrap:         config.WarmupStage{OfferedTxRate: 1, Duration: time.Second},
			Steady:            config.WarmupStage{OfferedTxRate: 1, Duration: time.Second},
			CompletionTimeout: time.Second,
		},
		Duration:  time.Second,
		Scenarios: mixedPlannerScenarios(),
		OutputDir: outputDir,
	})
	if err == nil || !strings.Contains(err.Error(), "rate is too large") {
		t.Fatalf("Run error = %v, want rate is too large", err)
	}
	if _, statErr := os.Stat(outputDir); !os.IsNotExist(statErr) {
		t.Fatalf("output directory was created before rate validation: %v", statErr)
	}
}

func TestWorkerPoolCanHoldOneSecondOfOfferedTraffic(t *testing.T) {
	tests := []struct {
		name string
		rate int
		want int
	}{
		{name: "minimum pool", rate: 1, want: 16},
		{name: "replay traffic", rate: 210, want: 210},
		{name: "original traffic", rate: 2_100, want: 2_100},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := workerCountForRate(test.rate); got != test.want {
				t.Fatalf("workerCountForRate(%d) = %d, want %d", test.rate, got, test.want)
			}
		})
	}
}

func TestRunAbortsOnPrewarmFailureBeforeStreamsWindowOrBusinessTraffic(t *testing.T) {
	outputDir := t.TempDir()
	runWindowPath := filepath.Join(outputDir, "run-window.json")
	var notificationStreamCalls atomic.Int64
	var businessCalls atomic.Int64
	client := &http.Client{Transport: roundTripperFunc(func(request *http.Request) (*http.Response, error) {
		if request.Method != http.MethodGet || request.URL.Path != "/health" {
			businessCalls.Add(1)
		}
		return http2Response(http.StatusInternalServerError), nil
	})}

	err := runWithDependencies(Config{
		ProfileName:   "prewarm-failure-test",
		BaseURL:       "https://localhost:8001",
		OfferedTxRate: 1,
		Warmup: config.Warmup{
			Bootstrap:         config.WarmupStage{OfferedTxRate: 1, Duration: time.Second},
			Steady:            config.WarmupStage{OfferedTxRate: 1, Duration: time.Second},
			CompletionTimeout: time.Second,
		},
		Duration:      time.Second,
		Scenarios:     mixedPlannerScenarios(),
		OutputDir:     outputDir,
		RunWindowPath: runWindowPath,
	}, runDependencies{
		newHTTPClients: func(Config, []ids.Pair) (map[string]*http.Client, error) {
			return map[string]*http.Client{"10000001": client}, nil
		},
		openNotificationPull: func(context.Context, string) (notificationPullClient, func() error, error) {
			notificationStreamCalls.Add(1)
			return nil, nil, errors.New("notification stream must not open")
		},
	})
	if err == nil || !strings.Contains(err.Error(), "prewarm central transfer HTTP/2 clients") {
		t.Fatalf("Run error = %v, want prewarm failure", err)
	}
	if notificationStreamCalls.Load() != 0 {
		t.Fatalf("notification stream calls = %d, want 0", notificationStreamCalls.Load())
	}
	if businessCalls.Load() != 0 {
		t.Fatalf("business HTTP calls = %d, want 0", businessCalls.Load())
	}
	if _, statErr := os.Stat(runWindowPath); !os.IsNotExist(statErr) {
		t.Fatalf("run window exists after prewarm failure: %v", statErr)
	}

	starts, readErr := events.ReadStarts(filepath.Join(outputDir, "pacs008-starts.csv"))
	if readErr != nil || len(starts) != 0 {
		t.Fatalf("PACS.008 rows = %d, error = %v, want none", len(starts), readErr)
	}
	statuses, readErr := events.ReadStatusStarts(filepath.Join(outputDir, "pacs002-starts.csv"))
	if readErr != nil || len(statuses) != 0 {
		t.Fatalf("PACS.002 rows = %d, error = %v, want none", len(statuses), readErr)
	}
	notifications, readErr := events.ReadNotifications(filepath.Join(outputDir, "notifications.csv"))
	if readErr != nil || len(notifications) != 0 {
		t.Fatalf("notification rows = %d, error = %v, want none", len(notifications), readErr)
	}
	replays, readErr := events.ReadReplays(filepath.Join(outputDir, "replays.csv"))
	if readErr != nil || len(replays) != 0 {
		t.Fatalf("replay rows = %d, error = %v, want none", len(replays), readErr)
	}
}

func TestExpiredPlannedOriginalHasNoPaymentEffects(t *testing.T) {
	planner, err := newWorkloadPlanner(mixedPlannerScenarios())
	if err != nil {
		t.Fatal(err)
	}
	selector, err := newReplaySelector(0.10)
	if err != nil {
		t.Fatal(err)
	}
	var payloadsBuilt atomic.Int64
	s := &simulator{
		cfg:                   Config{Scenarios: mixedPlannerScenarios()},
		runID:                 "deadline-test",
		originalPlanner:       planner,
		pacs008ReplaySelector: selector,
		paymentStates:         make(map[string]paymentState),
		buildPacs008Func: func(string, string, string, int64) []byte {
			payloadsBuilt.Add(1)
			return []byte("must not be built")
		},
	}

	tracker := newPhaseTracker()
	if err := tracker.Add(); err != nil {
		t.Fatal(err)
	}
	tracker.CloseGeneration()
	jobs := make(chan transferJob, 1)
	jobs <- s.planOriginal(
		time.Now().Add(-time.Second).UnixNano(),
		time.Now().Add(-time.Nanosecond),
		30*time.Second,
		tracker,
	)
	close(jobs)
	var workers sync.WaitGroup
	workers.Add(1)
	s.transferWorker(context.Background(), &workers, jobs)
	if err := tracker.Wait(context.Background()); err != nil {
		t.Fatalf("expired slot left warmup work pending: %v", err)
	}

	if payloadsBuilt.Load() != 0 {
		t.Fatalf("payloads built = %d, want 0", payloadsBuilt.Load())
	}
	if s.started.Load() != 0 {
		t.Fatalf("started originals = %d, want 0", s.started.Load())
	}
	if len(s.paymentStates) != 0 {
		t.Fatalf("payment states = %#v, want none", s.paymentStates)
	}
}

func TestSchedulerPlansCompleteOriginalJobBeforeWorkerDispatch(t *testing.T) {
	planner, err := newWorkloadPlanner(mixedPlannerScenarios())
	if err != nil {
		t.Fatal(err)
	}
	selector, err := newReplaySelector(0.10)
	if err != nil {
		t.Fatal(err)
	}
	s := &simulator{
		runID:                 "scheduler-owned-planning",
		originalPlanner:       planner,
		pacs008ReplaySelector: selector,
	}
	createdAt := time.Now().UnixNano()
	deadline := time.Now().Add(time.Second)

	job := s.planOriginal(createdAt, deadline, 30*time.Second, nil)

	if want := ids.TransactionID("scheduler-owned-planning", 0); job.ID != want {
		t.Fatalf("transaction ID = %q, want %q", job.ID, want)
	}
	if job.Pair.Payer == "" || job.Pair.Receiver == "" || job.ScenarioName == "" || job.Amount <= 0 {
		t.Fatalf("planned job is incomplete: %#v", job)
	}
	if job.Created != createdAt || !job.deadline.Equal(deadline) {
		t.Fatalf("planned timing = created %d deadline %s, want %d and %s", job.Created, job.deadline, createdAt, deadline)
	}
	if job.requestTimeout != 30*time.Second {
		t.Fatalf("request timeout = %s, want 30s", job.requestTimeout)
	}
}

func TestWarmupHTTPFailureDoesNotFailGateWhenExpectedOutcomeArrives(t *testing.T) {
	recorder := newTestEventRecorder(t, t.TempDir())

	tracker := newPhaseTracker()
	if err := tracker.Add(); err != nil {
		t.Fatal(err)
	}
	tracker.CloseGeneration()
	s := &simulator{
		cfg: configForOutcomeTest("ACSC", nil),
		httpClients: map[string]*http.Client{
			"10000001": {Transport: roundTripperFunc(func(*http.Request) (*http.Response, error) {
				return nil, errors.New("request timeout")
			})},
		},
		eventRecorder: recorder,
		paymentStates: make(map[string]paymentState),
		buildPacs008Func: func(string, string, string, int64) []byte {
			return []byte("pacs.008")
		},
	}
	job := transferJob{
		ID:           "tx-1",
		Pair:         ids.Pair{Payer: "10000001", Receiver: "20000001"},
		ScenarioName: "happy-path",
		tracker:      tracker,
	}

	s.sendPacs008At(context.Background(), job, time.Now())
	s.observePayerOutcome("tx-1", "10000001", "ACSC", nil)

	if err := tracker.Wait(context.Background()); err != nil {
		t.Fatalf("warmup gate failed after the expected outcome: %v", err)
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
		statusJobs: make(chan statusJob, 2),
		paymentStates: map[string]paymentState{
			"tx-1": {scenarioName: "happy-path", requestTimeout: 30 * time.Second},
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
	if job.requestTimeout != 30*time.Second {
		t.Fatalf("PACS.002 request timeout = %s, want 30s", job.requestTimeout)
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
					return &http.Response{StatusCode: http.StatusOK, Proto: "HTTP/2.0", ProtoMajor: 2, ProtoMinor: 0, Body: http.NoBody}, nil
				}),
			},
			"20000001": {
				Transport: roundTripperFunc(func(*http.Request) (*http.Response, error) {
					receiverCalls.Add(1)
					return &http.Response{StatusCode: http.StatusAccepted, Proto: "HTTP/2.0", ProtoMajor: 2, ProtoMinor: 0, Body: http.NoBody}, nil
				}),
			},
		},
	}

	payerStatus := s.post(
		context.Background(),
		"10000001",
		"https://localhost:8001/transfer",
		[]byte("pacs008"),
		defaultRequestTimeout,
	).HTTPStatus
	receiverStatus := s.post(
		context.Background(),
		"20000001",
		"https://localhost:8001/transfer/status",
		[]byte("pacs002"),
		defaultRequestTimeout,
	).HTTPStatus

	if payerStatus != http.StatusOK || payerCalls.Load() != 1 {
		t.Fatalf("payer status/calls = %d/%d", payerStatus, payerCalls.Load())
	}
	if receiverStatus != http.StatusAccepted || receiverCalls.Load() != 1 {
		t.Fatalf("receiver status/calls = %d/%d", receiverStatus, receiverCalls.Load())
	}
}

func TestNotificationPullAdvancesCursorOnlyAfterProcessingTheCompleteResponse(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	dir := t.TempDir()
	recorder := newTestEventRecorder(t, dir)

	client := newFakeNotificationPullClient()
	now := time.Now()
	s := &simulator{
		cfg:               Config{PullMetrics: pullmetrics.NewRecorder()},
		runID:             "tx",
		eventRecorder:     recorder,
		activeStartedAt:   now.Add(-time.Second),
		generationEndedAt: now.Add(time.Second),
	}
	var wg sync.WaitGroup
	wg.Add(1)
	go s.consumeNotificationPull(ctx, &wg, notificationPullSession{
		ispb:         "20000001",
		receiverRole: false,
		client:       client,
	})

	select {
	case request := <-client.requests:
		if request.GetCursor() != "" {
			t.Fatalf("first pull request = %#v", request)
		}
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for first pull")
	}

	client.responses <- &notificationpb.PullResponse{
		Notifications: []*notificationpb.Notification{{
			Payload:         payload.Pacs002("tx-1"),
			CommunicationId: "notification-1",
		}},
		NextCursor: "cursor-1",
	}

	select {
	case request := <-client.requests:
		if request.GetCursor() != "cursor-1" {
			t.Fatalf("next pull cursor = %q, want cursor-1", request.GetCursor())
		}
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for next pull")
	}

	cancel()
	wg.Wait()
	if err := recorder.Close(); err != nil {
		t.Fatal(err)
	}
	rows, err := events.ReadNotifications(filepath.Join(dir, "notifications.csv"))
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || rows[0].EndToEndID != "tx-1" || rows[0].StatusCode != "ACSP" || len(rows[0].ReasonCodes) != 0 {
		t.Fatalf("notification rows = %#v", rows)
	}
	metrics := s.cfg.PullMetrics.Snapshot()
	if metrics.Batches[1] != 1 || metrics.EmptyResponses != 0 {
		t.Fatalf("pull metrics = %#v", metrics)
	}
}

func TestNotificationPullIgnoresTransfersFromEarlierRuns(t *testing.T) {
	dir := t.TempDir()
	recorder := newTestEventRecorder(t, dir)
	s := &simulator{
		cfg:           Config{PullMetrics: pullmetrics.NewRecorder()},
		runID:         "current-run",
		eventRecorder: recorder,
	}

	err := s.processNotificationPull(
		context.Background(),
		notificationPullSession{ispb: "20000001", receiverRole: true},
		&notificationpb.PullResponse{
			Notifications: []*notificationpb.Notification{{
				Payload:         payload.Pacs008("earlier-run", "10000001", "20000001", 100),
				CommunicationId: "historical-notification",
			}},
			NextCursor: "earlier-cursor",
		},
		time.Now(),
	)
	if err != nil {
		t.Fatal(err)
	}
	if runErr := s.currentRunError(); runErr != nil {
		t.Fatalf("run error = %v, want historical notification ignored", runErr)
	}
	if err := recorder.Close(); err != nil {
		t.Fatal(err)
	}
	rows, err := events.ReadNotifications(filepath.Join(dir, "notifications.csv"))
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 0 || s.notifications.Load() != 0 || s.statusJobsQueued.Load() != 0 {
		t.Fatalf(
			"historical effects: rows=%d notifications=%d status_jobs=%d",
			len(rows),
			s.notifications.Load(),
			s.statusJobsQueued.Load(),
		)
	}
}

func TestCurrentTransferIsIdentifiedByTheExactRunPrefix(t *testing.T) {
	s := &simulator{runID: "go-123"}

	if !s.isCurrentTransfer("go-123-42") {
		t.Fatal("current transfer was not recognized")
	}
	if s.isCurrentTransfer("go-1234-42") {
		t.Fatal("similar run prefix was recognized as the current run")
	}
	if s.isCurrentTransfer("earlier-run-42") {
		t.Fatal("historical transfer was recognized as the current run")
	}
}

func TestNotificationPullRecordsRepeatedPhysicalDelivery(t *testing.T) {
	dir := t.TempDir()
	recorder := newTestEventRecorder(t, dir)
	s := &simulator{
		cfg:           Config{PullMetrics: pullmetrics.NewRecorder()},
		runID:         "tx",
		eventRecorder: recorder,
	}
	response := &notificationpb.PullResponse{Notifications: []*notificationpb.Notification{{
		Payload:         payload.Pacs002("tx-1"),
		CommunicationId: "same-communication",
	}}}

	if err := s.processNotificationPull(
		context.Background(), notificationPullSession{ispb: "10000001"}, response, time.Now(),
	); err != nil {
		t.Fatal(err)
	}
	if err := s.processNotificationPull(
		context.Background(), notificationPullSession{ispb: "10000001"}, response, time.Now(),
	); err != nil {
		t.Fatal(err)
	}
	if err := recorder.Close(); err != nil {
		t.Fatal(err)
	}
	rows, err := events.ReadNotifications(filepath.Join(dir, "notifications.csv"))
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 2 || s.notifications.Load() != 2 {
		t.Fatalf("physical notifications: rows=%d count=%d, want two", len(rows), s.notifications.Load())
	}
}

func TestOpenNotificationPullsClosesAlreadyOpenedSessionsOnFailure(t *testing.T) {
	var closed atomic.Int64
	openCount := 0
	s := &simulator{
		openNotificationPullFunc: func(context.Context, string) (notificationPullClient, func() error, error) {
			openCount++
			if openCount == 1 {
				return newFakeNotificationPullClient(), func() error {
					closed.Add(1)
					return nil
				}, nil
			}
			return nil, nil, errors.New("handshake failed")
		},
	}

	_, err := s.openNotificationPulls(context.Background(), []ids.Pair{
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

type fakeNotificationPullClient struct {
	requests  chan *notificationpb.PullRequest
	responses chan *notificationpb.PullResponse
}

func newFakeNotificationPullClient() *fakeNotificationPullClient {
	return &fakeNotificationPullClient{
		requests:  make(chan *notificationpb.PullRequest, 2),
		responses: make(chan *notificationpb.PullResponse, 1),
	}
}

func (f *fakeNotificationPullClient) PullNotifications(
	ctx context.Context,
	request *notificationpb.PullRequest,
	_ ...grpc.CallOption,
) (*notificationpb.PullResponse, error) {
	select {
	case f.requests <- request:
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	select {
	case response := <-f.responses:
		return response, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
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
