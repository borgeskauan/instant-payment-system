package sim

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"math"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/connectivity"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/status"

	"instant-payment-system/load-test/go-loadtool/internal/config"
	"instant-payment-system/load-test/go-loadtool/internal/events"
	"instant-payment-system/load-test/go-loadtool/internal/gen/notificationpb"
	"instant-payment-system/load-test/go-loadtool/internal/ids"
	"instant-payment-system/load-test/go-loadtool/internal/payload"
	"instant-payment-system/load-test/go-loadtool/internal/pullmetrics"
	"instant-payment-system/load-test/go-loadtool/internal/runwindow"
)

type Config struct {
	ProfileName                   string
	BaseURL                       string
	CentralTransferCACert         string
	CentralTransferClientCertRoot string
	CentralTransferServerName     string
	GatewayAddress                string
	GatewayCACert                 string
	GatewayClientCertRoot         string
	GatewayServerName             string
	PullMetrics                   *pullmetrics.Recorder
	OfferedTxRate                 int
	Warmup                        config.Warmup
	Duration                      time.Duration
	Drain                         time.Duration
	Replay                        config.Replay
	Scenarios                     []config.Scenario
	OutputDir                     string
	RunWindowPath                 string
}

type transferJob struct {
	ID             string
	Pair           ids.Pair
	Created        int64
	Amount         int64
	ScenarioName   string
	ReplaySelected bool
	tracker        *phaseTracker
}

type statusJob struct {
	receiverISPB string
	endToEndID   string
	scenarioName string
	tracker      *phaseTracker
}

type paymentState struct {
	payerISPB     string
	scenarioName  string
	tracker       *phaseTracker
	pacs002Queued bool
}

type simulator struct {
	cfg                      Config
	runID                    string
	httpClients              map[string]*http.Client
	startWriter              *events.StartWriter
	eventWriter              *events.NotificationWriter
	replayWriter             *events.ReplayWriter
	statusStartWriter        *events.StatusStartWriter
	replayScheduler          *replayScheduler
	startMu                  sync.Mutex
	eventMu                  sync.Mutex
	replayMu                 sync.Mutex
	statusStartMu            sync.Mutex
	pacs002SelectorMu        sync.Mutex
	originalMu               sync.Mutex
	paymentStatesMu          sync.Mutex
	runErrorMu               sync.Mutex
	windowMu                 sync.RWMutex
	runError                 error
	buildPacs008Func         func(string, string, string, int64) []byte
	buildPacs002Func         func(string) []byte
	sendPacs002Func          func(context.Context, statusJob)
	openNotificationPullFunc func(context.Context, string) (notificationPullClient, func() error, error)
	statusJobs               chan statusJob
	paymentStates            map[string]paymentState
	pacs002ReplaySelector    *replaySelector
	pacs008ReplaySelector    *replaySelector
	originalPlanner          *workloadPlanner
	nextOriginalSequence     uint64
	generationEndedAt        time.Time
	activeStartedAt          time.Time
	replayDeadlineAt         time.Time
	started                  atomic.Uint64
	accepted                 atomic.Uint64
	pacs002Sent              atomic.Uint64
	notifications            atomic.Uint64
	statusJobsQueued         atomic.Uint64
	replaysScheduled         atomic.Uint64
	replaysSent              atomic.Uint64
	replaysAccepted          atomic.Uint64
}

type notificationPullClient interface {
	PullNotifications(context.Context, *notificationpb.PullRequest, ...grpc.CallOption) (*notificationpb.PullResponse, error)
}

type notificationPullSession struct {
	ispb         string
	receiverRole bool
	client       notificationPullClient
	close        func() error
}

type grpcReadyConn interface {
	Connect()
	GetState() connectivity.State
	WaitForStateChange(context.Context, connectivity.State) bool
}

type runDependencies struct {
	newHTTPClients       func(Config, []ids.Pair) (map[string]*http.Client, error)
	openNotificationPull func(context.Context, string) (notificationPullClient, func() error, error)
}

func Run(cfg Config) error {
	return runWithDependencies(cfg, runDependencies{newHTTPClients: newHTTPClients})
}

func runWithDependencies(cfg Config, dependencies runDependencies) error {
	if cfg.OfferedTxRate <= 0 {
		return fmt.Errorf("rate must be positive")
	}
	if cfg.PullMetrics == nil {
		cfg.PullMetrics = pullmetrics.NewRecorder()
	}
	if _, err := maximumGeneratedTransfers(cfg.Warmup.OfferedTxRate, cfg.Warmup.Duration, cfg.OfferedTxRate, cfg.Duration); err != nil {
		return err
	}
	planner, err := newWorkloadPlanner(cfg.Scenarios)
	if err != nil {
		return err
	}
	var selector *replaySelector
	if cfg.Replay.Pacs008 != nil {
		selector, err = newReplaySelector(cfg.Replay.Pacs008.Share)
		if err != nil {
			return err
		}
	}
	if err := os.MkdirAll(cfg.OutputDir, 0o755); err != nil {
		return err
	}

	startWriter, err := events.NewStartWriter(filepath.Join(cfg.OutputDir, "pacs008-starts.csv"))
	if err != nil {
		return err
	}
	defer startWriter.Close()

	eventWriter, err := events.NewNotificationWriter(filepath.Join(cfg.OutputDir, "notifications.csv"))
	if err != nil {
		return err
	}
	defer eventWriter.Close()

	replayWriter, err := events.NewReplayWriter(filepath.Join(cfg.OutputDir, "replays.csv"))
	if err != nil {
		return err
	}
	defer replayWriter.Close()

	statusStartWriter, err := events.NewStatusStartWriter(filepath.Join(cfg.OutputDir, "pacs002-starts.csv"))
	if err != nil {
		return err
	}
	defer statusStartWriter.Close()

	var pacs002ReplaySelector *replaySelector
	if cfg.Replay.Pacs002 != nil {
		pacs002ReplaySelector, err = newReplaySelectorWithDomain(cfg.Replay.Pacs002.Share, pacs002ReplayShuffleDomain)
		if err != nil {
			return err
		}
	}

	pairs := pairsForScenarios(cfg.Scenarios)
	httpClients, err := dependencies.newHTTPClients(cfg, pairs)
	if err != nil {
		return err
	}
	defer closeHTTPClients(httpClients)

	s := &simulator{
		cfg:                      cfg,
		runID:                    fmt.Sprintf("go-%d", time.Now().UnixNano()),
		httpClients:              httpClients,
		startWriter:              startWriter,
		eventWriter:              eventWriter,
		replayWriter:             replayWriter,
		statusStartWriter:        statusStartWriter,
		statusJobs:               make(chan statusJob, statusQueueCapacity(cfg.OfferedTxRate)),
		paymentStates:            make(map[string]paymentState),
		pacs002ReplaySelector:    pacs002ReplaySelector,
		pacs008ReplaySelector:    selector,
		originalPlanner:          planner,
		openNotificationPullFunc: dependencies.openNotificationPull,
	}
	s.sendPacs002Func = s.sendPacs002

	rootCtx, cancel := context.WithCancel(context.Background())
	defer cancel()

	logPhase("prewarming central transfer HTTP/2 clients: psps=%d", len(httpClients))
	if err := prewarmHTTP2Clients(rootCtx, cfg.BaseURL, httpClients); err != nil {
		return fmt.Errorf("prewarm central transfer HTTP/2 clients: %w", err)
	}
	logPhase("central transfer HTTP/2 prewarm finished: psps=%d protocol=h2", len(httpClients))

	logPhase("prewarming notification pull clients: psps=%d", len(pairs)*2)
	notificationCtx, stopNotifications := context.WithCancel(rootCtx)
	sessions, err := s.openNotificationPulls(notificationCtx, pairs)
	if err != nil {
		stopNotifications()
		return err
	}
	logPhase("notification pull clients ready")

	windowPath := cfg.RunWindowPath
	if windowPath == "" {
		windowPath = filepath.Join(cfg.OutputDir, "run-window.json")
	}
	var pulls sync.WaitGroup
	for _, session := range sessions {
		pulls.Add(1)
		go s.consumeNotificationPull(notificationCtx, &pulls, session)
	}
	experimentCtx, stopExperiment := context.WithCancel(rootCtx)
	defer stopExperiment()

	var replayWorkers sync.WaitGroup
	if cfg.Replay.Pacs008 != nil || cfg.Replay.Pacs002 != nil {
		replayShare := 0.0
		if cfg.Replay.Pacs008 != nil {
			replayShare += cfg.Replay.Pacs008.Share
		}
		if cfg.Replay.Pacs002 != nil {
			replayShare += cfg.Replay.Pacs002.Share
		}
		replayRate := int(math.Ceil(float64(cfg.OfferedTxRate) * replayShare))
		replayWorkerCount := workerCountForRate(max(1, replayRate))
		s.replayScheduler = newReplayScheduler(experimentCtx, replayWorkerCount)
		s.startReplayWorkers(experimentCtx, &replayWorkers, s.replayScheduler.Ready(), replayWorkerCount)
		if cfg.Replay.Pacs008 != nil {
			logPhase("pacs.008 replay enabled: share=%.2f delay=%s workers=%d", cfg.Replay.Pacs008.Share, cfg.Replay.Pacs008.Delay, replayWorkerCount)
		}
		if cfg.Replay.Pacs002 != nil {
			logPhase("pacs.002 replay enabled: share=%.2f delay=%s workers=%d", cfg.Replay.Pacs002.Share, cfg.Replay.Pacs002.Delay, replayWorkerCount)
		}
	}

	statusWorkerCount := workerCountForRate(max(cfg.OfferedTxRate, cfg.Warmup.OfferedTxRate))
	var statusWorkers sync.WaitGroup
	s.startStatusWorkers(experimentCtx, &statusWorkers, s.statusJobs, statusWorkerCount)

	jobs := make(chan originalSlot)
	var workers sync.WaitGroup
	workerCount := workerCountForRate(max(cfg.OfferedTxRate, cfg.Warmup.OfferedTxRate))
	logPhase("starting warmup: offered_rate=%d/s duration=%s completion_timeout=%s workers=%d status_workers=%d", cfg.Warmup.OfferedTxRate, cfg.Warmup.Duration, cfg.Warmup.CompletionTimeout, workerCount, statusWorkerCount)
	for range workerCount {
		workers.Add(1)
		go s.transferWorker(experimentCtx, &workers, jobs)
	}

	var closeJobsOnce sync.Once
	closeJobs := func() { closeJobsOnce.Do(func() { close(jobs) }) }
	var shutdownOnce sync.Once
	shutdown := func() {
		shutdownOnce.Do(func() {
			stopExperiment()
			stopNotifications()
			closeNotificationSessions(sessions)
			closeJobs()
			workers.Wait()
			pulls.Wait()
			close(s.statusJobs)
			statusWorkers.Wait()
			if s.replayScheduler != nil {
				s.replayScheduler.Close()
				s.replayScheduler.Wait()
				replayWorkers.Wait()
			}
		})
	}
	defer shutdown()

	warmupTracker := newPhaseTracker()
	generationStartedAt := time.Now()
	warmupEndedAt := generationStartedAt.Add(cfg.Warmup.Duration)
	s.generateOriginalPhase(experimentCtx, jobs, generationStartedAt, warmupEndedAt, cfg.Warmup.OfferedTxRate, warmupTracker)
	warmupTracker.CloseGeneration()
	logPhase("warmup generation finished; waiting for observable warmup work: timeout=%s", cfg.Warmup.CompletionTimeout)
	warmupCtx, cancelWarmup := context.WithDeadline(experimentCtx, warmupEndedAt.Add(cfg.Warmup.CompletionTimeout))
	warmupErr := warmupTracker.Wait(warmupCtx)
	cancelWarmup()
	if warmupErr != nil {
		return fmt.Errorf("warmup completion gate: %w", warmupErr)
	}
	if err := s.currentRunError(); err != nil {
		return err
	}

	activeStartedAt := time.Now()
	generationEndedAt := activeStartedAt.Add(cfg.Duration)
	replayDeadlineAt := generationEndedAt.Add(cfg.Drain)
	s.setExecutionWindow(activeStartedAt, generationEndedAt, replayDeadlineAt)
	windowDocument := runwindow.New(cfg.ProfileName, generationStartedAt, warmupEndedAt, activeStartedAt, cfg.Duration, cfg.Drain, cfg.Replay)
	if err := runwindow.Write(windowPath, windowDocument); err != nil {
		return err
	}
	logPhase("warmup work completed; starting active load: offered_rate=%d/s duration=%s", cfg.OfferedTxRate, cfg.Duration)
	s.generateOriginalPhase(experimentCtx, jobs, activeStartedAt, generationEndedAt, cfg.OfferedTxRate, nil)
	closeJobs()
	logPhase("load generation finished; waiting for in-flight HTTP requests")
	workers.Wait()
	logPhase("original HTTP workers finished; waiting until fixed replay deadline: deadline=%s", replayDeadlineAt.Format(time.RFC3339Nano))
	if wait := time.Until(replayDeadlineAt); wait > 0 {
		timer := time.NewTimer(wait)
		select {
		case <-experimentCtx.Done():
			stopTimer(timer)
		case <-timer.C:
		}
	}
	logPhase("replay deadline reached; closing notification pulls")
	shutdown()
	logPhase("notification pulls, status workers, and replay workers closed")

	fmt.Printf("started=%d accepted=%d replays_scheduled=%d replays_sent=%d replays_accepted=%d notifications=%d pacs002_queued=%d pacs002_sent=%d output=%s\n",
		s.started.Load(),
		s.accepted.Load(),
		s.replaysScheduled.Load(),
		s.replaysSent.Load(),
		s.replaysAccepted.Load(),
		s.notifications.Load(),
		s.statusJobsQueued.Load(),
		s.pacs002Sent.Load(),
		cfg.OutputDir,
	)
	return s.currentRunError()
}

func logPhase(format string, args ...any) {
	fmt.Printf("[%s] %s\n", time.Now().Format("2006-01-02 15:04:05"), fmt.Sprintf(format, args...))
}

func newHTTPClients(cfg Config, pairs []ids.Pair) (map[string]*http.Client, error) {
	baseURL, err := url.Parse(cfg.BaseURL)
	if err != nil {
		return nil, fmt.Errorf("parse central transfer URL: %w", err)
	}
	if baseURL.Scheme != "https" {
		return nil, fmt.Errorf("central transfer URL must use https: %s", cfg.BaseURL)
	}

	rootCAs, err := loadCertificatePool(cfg.CentralTransferCACert, "central transfer CA")
	if err != nil {
		return nil, err
	}

	clients := make(map[string]*http.Client, len(pairs)*2)
	for _, pair := range pairs {
		for _, ispb := range []string{pair.Payer, pair.Receiver} {
			if _, exists := clients[ispb]; exists {
				continue
			}

			certPath, keyPath := clientCertPaths(cfg.CentralTransferClientCertRoot, ispb)
			certificate, err := tls.LoadX509KeyPair(certPath, keyPath)
			if err != nil {
				return nil, fmt.Errorf("load central transfer client certificate for ISPB %s: %w", ispb, err)
			}
			clients[ispb] = &http.Client{
				Transport: newHTTP2Transport(&tls.Config{
					MinVersion:   tls.VersionTLS12,
					ServerName:   cfg.CentralTransferServerName,
					RootCAs:      rootCAs,
					Certificates: []tls.Certificate{certificate},
				}),
				Timeout: 5 * time.Second,
			}
		}
	}
	return clients, nil
}

func closeHTTPClients(clients map[string]*http.Client) {
	for _, client := range clients {
		client.CloseIdleConnections()
	}
}

func workerCountForRate(rate int) int {
	return max(16, min(512, rate/2))
}

func statusQueueCapacity(rate int) int {
	return max(1024, rate*4)
}

func buildPairs(pairNumberStart int, count int) []ids.Pair {
	pairs := make([]ids.Pair, 0, count)
	for i := pairNumberStart; i < pairNumberStart+count; i++ {
		pairs = append(pairs, ids.PSPPair(i))
	}
	return pairs
}

func pairsForScenarios(scenarios []config.Scenario) []ids.Pair {
	var pairs []ids.Pair
	for _, scenario := range scenarios {
		participants := scenario.Participants
		pairs = append(pairs, buildPairs(participants.PairNumberStart, participants.HotPairCount+participants.ColdPairCount)...)
	}
	return pairs
}

func (s *simulator) generateOriginalPhase(ctx context.Context, jobs chan<- originalSlot, phaseStart, phaseEnd time.Time, rate int, tracker *phaseTracker) {
	slotCount := originalPhaseSlotCount(rate, phaseEnd.Sub(phaseStart))
	timer := time.NewTimer(time.Hour)
	stopTimer(timer)
	defer stopTimer(timer)
	for bucketIndex := uint64(0); ; bucketIndex++ {
		currentBucket := currentOriginalBucketIndex(phaseStart, time.Now())
		if currentBucket > bucketIndex {
			bucketIndex = currentBucket
		}
		bucket, exists := originalBucketAt(phaseStart, phaseEnd, rate, bucketIndex)
		if !exists {
			return
		}
		if bucket.firstSlot >= slotCount {
			return
		}
		if bucket.endSlot == bucket.firstSlot {
			continue
		}
		if !waitUntil(ctx, timer, bucket.start, bucket.end) {
			return
		}
		if !originalSlotCanStart(time.Now(), bucket.end) {
			if bucket.endSlot >= slotCount {
				return
			}
			continue
		}

		resetTimer(timer, time.Until(bucket.end))
		bucketExpired := false
		for slotIndex := bucket.firstSlot; slotIndex < bucket.endSlot && slotIndex < slotCount; slotIndex++ {
			if !s.addPhaseWork(tracker) {
				return
			}
			slot := originalSlot{createdAt: time.Now().UnixNano(), deadline: bucket.end, tracker: tracker}
			select {
			case jobs <- slot:
			case <-ctx.Done():
				s.completePhaseWork(tracker)
				return
			case <-timer.C:
				s.completePhaseWork(tracker)
				bucketExpired = true
			}
			if bucketExpired {
				break
			}
		}
		stopTimer(timer)
		if bucket.endSlot >= slotCount {
			return
		}
	}
}

func waitUntil(ctx context.Context, timer *time.Timer, target time.Time, end time.Time) bool {
	if !target.Before(end) {
		return false
	}
	wait := time.Until(target)
	if wait <= 0 {
		return true
	}
	resetTimer(timer, wait)
	select {
	case <-ctx.Done():
		stopTimer(timer)
		return false
	case <-timer.C:
		return true
	}
}

func (s *simulator) transferJobForSequence(seq uint64, planned plannedTransfer) transferJob {
	return s.transferJobForSequenceCreatedAt(seq, planned, time.Now().UnixNano())
}

func (s *simulator) transferJobForSequenceCreatedAt(seq uint64, planned plannedTransfer, createdAt int64) transferJob {
	return transferJob{
		ID:           ids.TransactionID(s.runID, seq),
		Pair:         planned.Pair,
		Created:      createdAt,
		Amount:       planned.Amount,
		ScenarioName: planned.ScenarioName,
	}
}

func (s *simulator) transferWorker(ctx context.Context, wg *sync.WaitGroup, jobs <-chan originalSlot) {
	defer wg.Done()
	for {
		select {
		case <-ctx.Done():
			return
		case slot, ok := <-jobs:
			if !ok {
				return
			}
			job, startedAt, claimed := s.claimOriginal(slot)
			if claimed {
				s.sendPacs008At(ctx, job, startedAt)
			} else {
				s.completePhaseWork(slot.tracker)
			}
		}
	}
}

func (s *simulator) claimOriginal(slot originalSlot) (transferJob, time.Time, bool) {
	if !originalSlotCanStart(time.Now(), slot.deadline) {
		return transferJob{}, time.Time{}, false
	}
	s.originalMu.Lock()
	defer s.originalMu.Unlock()
	startedAt := time.Now()
	if !originalSlotCanStart(startedAt, slot.deadline) {
		return transferJob{}, time.Time{}, false
	}
	planned := s.originalPlanner.Next()
	job := s.transferJobForSequenceCreatedAt(s.nextOriginalSequence, planned, slot.createdAt)
	job.tracker = slot.tracker
	s.nextOriginalSequence++
	if s.pacs008ReplaySelector != nil {
		job.ReplaySelected = s.pacs008ReplaySelector.Next()
	}
	return job, startedAt, true
}

func (s *simulator) sendPacs008(ctx context.Context, job transferJob) {
	s.sendPacs008At(ctx, job, time.Now())
}

func (s *simulator) sendPacs008At(ctx context.Context, job transferJob, startedAtTime time.Time) {
	defer s.completePhaseWork(job.tracker)
	if !s.addPhaseWork(job.tracker) {
		return
	}
	buildPayload := payload.Pacs008
	if s.buildPacs008Func != nil {
		buildPayload = s.buildPacs008Func
	}
	body := buildPayload(job.ID, job.Pair.Payer, job.Pair.Receiver, job.Amount)
	startedAt := startedAtTime.UnixNano()
	s.paymentStatesMu.Lock()
	if s.paymentStates == nil {
		s.paymentStates = make(map[string]paymentState)
	}
	s.paymentStates[job.ID] = paymentState{
		payerISPB:    job.Pair.Payer,
		scenarioName: job.ScenarioName,
		tracker:      job.tracker,
	}
	s.paymentStatesMu.Unlock()
	if job.ReplaySelected {
		if !s.addPhaseWork(job.tracker) {
			return
		}
		if s.replayScheduler == nil || s.cfg.Replay.Pacs008 == nil {
			err := fmt.Errorf("selected pacs.008 replay %q has no configured scheduler", job.ID)
			s.failPhase(job.tracker, err)
			s.completePhaseWork(job.tracker)
		} else {
			err := s.replayScheduler.Schedule(replayJob{
				endToEndID:   job.ID,
				senderISPB:   job.Pair.Payer,
				scenarioName: job.ScenarioName,
				messageType:  events.MessagePacs008,
				endpoint:     "/transfer",
				body:         body,
				dueAt:        startedAtTime.Add(s.cfg.Replay.Pacs008.Delay),
				tracker:      job.tracker,
			})
			if err != nil {
				s.failPhase(job.tracker, fmt.Errorf("schedule pacs.008 replay %q: %w", job.ID, err))
				s.completePhaseWork(job.tracker)
			} else {
				s.replaysScheduled.Add(1)
			}
		}
	}
	attempt := s.post(ctx, job.Pair.Payer, fmt.Sprintf("%s/transfer", s.cfg.BaseURL), body)
	status := attempt.HTTPStatus
	doneAt := time.Now().UnixNano()
	s.started.Add(1)
	if status >= 200 && status < 300 {
		s.accepted.Add(1)
	}
	s.writeStart(events.Start{
		EndToEndID:             job.ID,
		PayerISPB:              job.Pair.Payer,
		ReceiverISPB:           job.Pair.Receiver,
		CreatedAtNS:            job.Created,
		RequestStartedAtNS:     startedAt,
		RequestDoneAtNS:        doneAt,
		HTTPStatus:             status,
		ScenarioName:           job.ScenarioName,
		Pacs008ReplaySelected:  job.ReplaySelected,
		ConnectionAcquiredAtNS: attempt.ConnectionAcquiredAtNS,
		RequestWrittenAtNS:     attempt.RequestWrittenAtNS,
		ConnectionReused:       attempt.ConnectionReused,
	})
}

func (s *simulator) sendReplay(ctx context.Context, job replayJob) {
	defer s.completePhaseWork(job.tracker)
	_, _, replayDeadlineAt := s.executionWindow()
	if !time.Now().Before(replayDeadlineAt) && !replayDeadlineAt.IsZero() {
		if job.tracker != nil {
			s.failPhase(job.tracker, fmt.Errorf("warmup %s replay %q reached the experiment deadline before starting", job.messageType, job.endToEndID))
		}
		return
	}
	startedAt := time.Now().UnixNano()
	attempt := s.post(ctx, job.senderISPB, s.cfg.BaseURL+job.endpoint, job.body)
	status := attempt.HTTPStatus
	doneAt := time.Now().UnixNano()
	s.replaysSent.Add(1)
	if status >= 200 && status < 300 {
		s.replaysAccepted.Add(1)
	}
	s.writeReplay(events.Replay{
		EndToEndID:             job.endToEndID,
		SenderISPB:             job.senderISPB,
		ScenarioName:           job.scenarioName,
		MessageType:            job.messageType,
		RequestStartedAtNS:     startedAt,
		RequestDoneAtNS:        doneAt,
		HTTPStatus:             status,
		ConnectionAcquiredAtNS: attempt.ConnectionAcquiredAtNS,
		RequestWrittenAtNS:     attempt.RequestWrittenAtNS,
		ConnectionReused:       attempt.ConnectionReused,
	})
}

func (s *simulator) startReplayWorkers(ctx context.Context, workers *sync.WaitGroup, jobs <-chan replayJob, workerCount int) {
	for range workerCount {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for job := range jobs {
				s.sendReplay(ctx, job)
			}
		}()
	}
}

func (s *simulator) sendPacs002(ctx context.Context, job statusJob) {
	defer s.completePhaseWork(job.tracker)
	_, generationEndedAt, replayDeadlineAt := s.executionWindow()
	if !time.Now().Before(replayDeadlineAt) && !replayDeadlineAt.IsZero() {
		if job.tracker != nil {
			s.failPhase(job.tracker, fmt.Errorf("warmup pacs.002 original %q reached the experiment deadline before starting", job.endToEndID))
		}
		return
	}
	buildPayload := payload.Pacs002
	if s.buildPacs002Func != nil {
		buildPayload = s.buildPacs002Func
	}
	body := buildPayload(job.endToEndID)
	startedAtTime := time.Now()
	selected := false
	if s.pacs002ReplaySelector != nil && (generationEndedAt.IsZero() || startedAtTime.Before(generationEndedAt)) {
		s.pacs002SelectorMu.Lock()
		selected = s.pacs002ReplaySelector.Next()
		s.pacs002SelectorMu.Unlock()
	}
	if selected {
		if !s.addPhaseWork(job.tracker) {
			return
		}
		if s.replayScheduler == nil || s.cfg.Replay.Pacs002 == nil {
			err := fmt.Errorf("selected pacs.002 replay %q has no configured scheduler", job.endToEndID)
			s.failPhase(job.tracker, err)
			s.completePhaseWork(job.tracker)
		} else if err := s.replayScheduler.Schedule(replayJob{
			endToEndID:   job.endToEndID,
			senderISPB:   job.receiverISPB,
			scenarioName: job.scenarioName,
			messageType:  events.MessagePacs002,
			endpoint:     "/transfer/status",
			body:         body,
			dueAt:        startedAtTime.Add(s.cfg.Replay.Pacs002.Delay),
			tracker:      job.tracker,
		}); err != nil {
			s.failPhase(job.tracker, fmt.Errorf("schedule pacs.002 replay %q: %w", job.endToEndID, err))
			s.completePhaseWork(job.tracker)
		} else {
			s.replaysScheduled.Add(1)
		}
	}
	attempt := s.post(
		ctx,
		job.receiverISPB,
		fmt.Sprintf("%s/transfer/status", s.cfg.BaseURL),
		body,
	)
	status := attempt.HTTPStatus
	doneAt := time.Now().UnixNano()
	s.writeStatusStart(events.StatusStart{
		EndToEndID:             job.endToEndID,
		SenderISPB:             job.receiverISPB,
		ScenarioName:           job.scenarioName,
		RequestStartedAtNS:     startedAtTime.UnixNano(),
		RequestDoneAtNS:        doneAt,
		HTTPStatus:             status,
		Pacs002ReplaySelected:  selected,
		ConnectionAcquiredAtNS: attempt.ConnectionAcquiredAtNS,
		RequestWrittenAtNS:     attempt.RequestWrittenAtNS,
		ConnectionReused:       attempt.ConnectionReused,
	})
	if status < 200 || status >= 300 {
		return
	}
	s.pacs002Sent.Add(1)
	s.writeNotification(events.Notification{
		EndToEndID:   job.endToEndID,
		ISPB:         job.receiverISPB,
		EventType:    events.EventPacs002Sent,
		ReceivedAtNS: doneAt,
	})
}

func (s *simulator) enqueuePacs002(ctx context.Context, receiverISPB string, endToEndID string) {
	s.paymentStatesMu.Lock()
	state, exists := s.paymentStates[endToEndID]
	if !exists || state.pacs002Queued {
		s.paymentStatesMu.Unlock()
		return
	}
	state.pacs002Queued = true
	s.paymentStates[endToEndID] = state
	scenarioName := state.scenarioName
	tracker := state.tracker
	s.paymentStatesMu.Unlock()
	if !s.addPhaseWork(tracker) {
		return
	}
	select {
	case s.statusJobs <- statusJob{receiverISPB: receiverISPB, endToEndID: endToEndID, scenarioName: scenarioName, tracker: tracker}:
		s.statusJobsQueued.Add(1)
	case <-ctx.Done():
		s.completePhaseWork(tracker)
	}
}

func (s *simulator) startStatusWorkers(
	ctx context.Context,
	workers *sync.WaitGroup,
	jobs <-chan statusJob,
	workerCount int,
) {
	for range workerCount {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for {
				select {
				case <-ctx.Done():
					return
				case job, ok := <-jobs:
					if !ok {
						return
					}
					s.sendPacs002Func(ctx, job)
				}
			}
		}()
	}
}

func (s *simulator) openNotificationPulls(
	ctx context.Context,
	pairs []ids.Pair,
) ([]notificationPullSession, error) {
	specs := notificationStreamSpecs(pairs)
	sessions := make([]notificationPullSession, 0, len(specs))

	for _, spec := range specs {
		open := s.openNotificationPull
		if s.openNotificationPullFunc != nil {
			open = s.openNotificationPullFunc
		}
		client, closeFunc, err := open(ctx, spec.ispb)
		if err != nil {
			closeNotificationSessions(sessions)
			return nil, fmt.Errorf("open notification pull client for ISPB %s: %w", spec.ispb, err)
		}
		sessions = append(sessions, notificationPullSession{
			ispb:         spec.ispb,
			receiverRole: spec.receiverRole,
			client:       client,
			close:        closeFunc,
		})
	}

	return sessions, nil
}

type notificationStreamSpec struct {
	ispb         string
	receiverRole bool
}

func notificationStreamSpecs(pairs []ids.Pair) []notificationStreamSpec {
	specs := make([]notificationStreamSpec, 0, len(pairs)*2)
	seen := make(map[string]int, len(pairs)*2)
	for _, pair := range pairs {
		if index, ok := seen[pair.Receiver]; ok {
			specs[index].receiverRole = true
		} else {
			seen[pair.Receiver] = len(specs)
			specs = append(specs, notificationStreamSpec{ispb: pair.Receiver, receiverRole: true})
		}
		if _, ok := seen[pair.Payer]; !ok {
			seen[pair.Payer] = len(specs)
			specs = append(specs, notificationStreamSpec{ispb: pair.Payer})
		}
	}
	return specs
}

func (s *simulator) openNotificationPull(
	ctx context.Context,
	ispb string,
) (notificationPullClient, func() error, error) {
	transportCredentials, err := s.notificationTransportCredentials(ispb)
	if err != nil {
		return nil, nil, err
	}

	conn, err := grpc.NewClient(s.cfg.GatewayAddress, grpc.WithTransportCredentials(transportCredentials))
	if err != nil {
		return nil, nil, err
	}
	if err := waitForGrpcReady(ctx, conn); err != nil {
		_ = conn.Close()
		return nil, nil, err
	}

	client := notificationpb.NewNotificationGatewayClient(conn)
	return client, conn.Close, nil
}

func waitForGrpcReady(ctx context.Context, conn grpcReadyConn) error {
	conn.Connect()
	for {
		state := conn.GetState()
		switch state {
		case connectivity.Ready:
			return nil
		case connectivity.Shutdown:
			return fmt.Errorf("notification gateway channel reached SHUTDOWN before READY")
		}
		if !conn.WaitForStateChange(ctx, state) {
			if err := ctx.Err(); err != nil {
				return fmt.Errorf("wait for notification gateway channel READY: %w", err)
			}
			return fmt.Errorf("notification gateway channel did not reach READY")
		}
	}
}

func (s *simulator) notificationTransportCredentials(ispb string) (credentials.TransportCredentials, error) {
	certPath, keyPath := clientCertPaths(s.cfg.GatewayClientCertRoot, ispb)

	certificate, err := tls.LoadX509KeyPair(certPath, keyPath)
	if err != nil {
		return nil, fmt.Errorf("load client certificate for ISPB %s: %w", ispb, err)
	}

	rootCAs, err := loadCertificatePool(s.cfg.GatewayCACert, "gateway CA")
	if err != nil {
		return nil, err
	}

	serverName := s.cfg.GatewayServerName
	if serverName == "" {
		serverName = "localhost"
	}
	return credentials.NewTLS(&tls.Config{
		MinVersion:   tls.VersionTLS12,
		ServerName:   serverName,
		RootCAs:      rootCAs,
		Certificates: []tls.Certificate{certificate},
	}), nil
}

func loadCertificatePool(path string, description string) (*x509.CertPool, error) {
	caPEM, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read %s certificate: %w", description, err)
	}
	rootCAs := x509.NewCertPool()
	if !rootCAs.AppendCertsFromPEM(caPEM) {
		return nil, fmt.Errorf("%s certificate has no valid PEM certificates: %s", description, path)
	}
	return rootCAs, nil
}

func clientCertPaths(root string, ispb string) (string, string) {
	base := filepath.Join(root, "psp-"+ispb)
	return filepath.Join(base, "client.crt"), filepath.Join(base, "client.key")
}

func closeNotificationSessions(sessions []notificationPullSession) {
	for _, session := range sessions {
		if session.close != nil {
			_ = session.close()
		}
	}
}

func (s *simulator) consumeNotificationPull(ctx context.Context, wg *sync.WaitGroup, session notificationPullSession) {
	defer wg.Done()
	cursor := ""
	for {
		response, err := session.client.PullNotifications(ctx, &notificationpb.PullRequest{
			Cursor: cursor,
		})
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			code := status.Code(err)
			if code != codes.Unavailable && code != codes.DeadlineExceeded {
				s.recordRunError(fmt.Errorf("notification pull %s: %w", session.ispb, err))
				return
			}
			fmt.Fprintf(os.Stderr, "notification pull %s failed: %v\n", session.ispb, err)
			timer := time.NewTimer(100 * time.Millisecond)
			select {
			case <-ctx.Done():
				stopTimer(timer)
				return
			case <-timer.C:
			}
			continue
		}
		receivedAt := time.Now()
		activeStartedAt, generationEndedAt, _ := s.executionWindow()
		if !activeStartedAt.IsZero() && !receivedAt.Before(activeStartedAt) && receivedAt.Before(generationEndedAt) {
			s.cfg.PullMetrics.Observe(len(response.Notifications))
		}
		if err := s.processNotificationPull(ctx, session, response, receivedAt); err != nil {
			s.recordRunError(fmt.Errorf("process notification pull for ISPB %s: %w", session.ispb, err))
			return
		}
		cursor = response.NextCursor
	}
}

func (s *simulator) processNotificationPull(
	ctx context.Context,
	session notificationPullSession,
	response *notificationpb.PullResponse,
	receivedAt time.Time,
) error {
	extracted := make([][]payload.Notification, len(response.Notifications))
	for index, message := range response.Notifications {
		if message.GetCommunicationId() == "" {
			return fmt.Errorf("notification has no communication_id")
		}
		notifications, err := payload.ExtractNotifications(message.Payload)
		if err != nil {
			return err
		}
		extracted[index] = notifications
	}
	for _, notifications := range extracted {
		for _, notification := range notifications {
			if !s.isCurrentTransfer(notification.EndToEndID) {
				continue
			}
			s.notifications.Add(1)
			switch notification.Kind {
			case payload.KindPacs008:
				s.writeNotification(events.Notification{
					EndToEndID:   notification.EndToEndID,
					ISPB:         session.ispb,
					EventType:    events.EventPacs008Received,
					ReceivedAtNS: receivedAt.UnixNano(),
				})
				if session.receiverRole {
					s.enqueuePacs002(ctx, session.ispb, notification.EndToEndID)
				}
			case payload.KindPacs002:
				s.writeNotification(events.Notification{
					EndToEndID:   notification.EndToEndID,
					ISPB:         session.ispb,
					EventType:    events.EventPacs002Received,
					ReceivedAtNS: receivedAt.UnixNano(),
					StatusCode:   notification.StatusCode,
					ReasonCodes:  notification.ReasonCodes,
				})
				s.observePayerOutcome(notification.EndToEndID, session.ispb, notification.StatusCode, notification.ReasonCodes)
			}
		}
	}
	return nil
}

func (s *simulator) observePayerOutcome(endToEndID, ispb, statusCode string, reasonCodes []string) {
	s.paymentStatesMu.Lock()
	state, exists := s.paymentStates[endToEndID]
	if !exists {
		s.paymentStatesMu.Unlock()
		return
	}
	if state.payerISPB != ispb {
		s.paymentStatesMu.Unlock()
		return
	}
	expectation, exists := s.payerNotificationExpectation(state.scenarioName)
	if !exists {
		tracker := state.tracker
		scenarioName := state.scenarioName
		s.paymentStatesMu.Unlock()
		s.failPhase(tracker, fmt.Errorf("warmup payment %q has unknown scenario %q", endToEndID, scenarioName))
		return
	}
	if statusCode != expectation.Status || !sameReasonCodes(reasonCodes, expectation.ReasonCodes) {
		tracker := state.tracker
		s.paymentStatesMu.Unlock()
		s.failPhase(tracker, fmt.Errorf(
			"warmup payment %q received contradictory payer outcome status=%q reasons=%v, want status=%q reasons=%v",
			endToEndID, statusCode, reasonCodes, expectation.Status, expectation.ReasonCodes))
		return
	}
	tracker := state.tracker
	delete(s.paymentStates, endToEndID)
	s.paymentStatesMu.Unlock()
	s.completePhaseWork(tracker)
}

func (s *simulator) payerNotificationExpectation(scenarioName string) (config.PayerNotificationExpectation, bool) {
	for _, scenario := range s.cfg.Scenarios {
		if scenario.Name == scenarioName {
			return scenario.Expectations.PayerNotification, true
		}
	}
	return config.PayerNotificationExpectation{}, false
}

func sameReasonCodes(left, right []string) bool {
	if len(left) != len(right) {
		return false
	}
	leftCopy := append([]string(nil), left...)
	rightCopy := append([]string(nil), right...)
	sort.Strings(leftCopy)
	sort.Strings(rightCopy)
	for index := range leftCopy {
		if leftCopy[index] != rightCopy[index] {
			return false
		}
	}
	return true
}

func (s *simulator) isCurrentTransfer(endToEndID string) bool {
	return s.runID != "" && strings.HasPrefix(endToEndID, s.runID+"-")
}

func (s *simulator) writeStart(row events.Start) {
	s.startMu.Lock()
	defer s.startMu.Unlock()
	if err := s.startWriter.Write(row); err != nil {
		s.recordRunError(fmt.Errorf("write start: %w", err))
	}
}

func (s *simulator) writeNotification(row events.Notification) {
	s.eventMu.Lock()
	defer s.eventMu.Unlock()
	if err := s.eventWriter.Write(row); err != nil {
		s.recordRunError(fmt.Errorf("write notification: %w", err))
	}
}

func (s *simulator) writeReplay(row events.Replay) {
	s.replayMu.Lock()
	defer s.replayMu.Unlock()
	if err := s.replayWriter.Write(row); err != nil {
		s.recordRunError(fmt.Errorf("write replay: %w", err))
	}
}

func (s *simulator) writeStatusStart(row events.StatusStart) {
	s.statusStartMu.Lock()
	defer s.statusStartMu.Unlock()
	if err := s.statusStartWriter.Write(row); err != nil {
		s.recordRunError(fmt.Errorf("write status start: %w", err))
	}
}

func (s *simulator) recordRunError(err error) {
	if err == nil {
		return
	}
	s.runErrorMu.Lock()
	defer s.runErrorMu.Unlock()
	if s.runError == nil {
		s.runError = err
	}
}

func (s *simulator) setExecutionWindow(activeStartedAt, generationEndedAt, replayDeadlineAt time.Time) {
	s.windowMu.Lock()
	defer s.windowMu.Unlock()
	s.activeStartedAt = activeStartedAt
	s.generationEndedAt = generationEndedAt
	s.replayDeadlineAt = replayDeadlineAt
}

func (s *simulator) executionWindow() (time.Time, time.Time, time.Time) {
	s.windowMu.RLock()
	defer s.windowMu.RUnlock()
	return s.activeStartedAt, s.generationEndedAt, s.replayDeadlineAt
}

func (s *simulator) addPhaseWork(tracker *phaseTracker) bool {
	if tracker == nil {
		return true
	}
	if err := tracker.Add(); err != nil {
		s.failPhase(tracker, err)
		return false
	}
	return true
}

func (s *simulator) completePhaseWork(tracker *phaseTracker) {
	if tracker == nil {
		return
	}
	if err := tracker.Done(); err != nil {
		s.failPhase(tracker, err)
	}
}

func (s *simulator) failPhase(tracker *phaseTracker, err error) {
	if err == nil {
		return
	}
	s.recordRunError(err)
	if tracker != nil {
		tracker.Fail(err)
	}
}

func (s *simulator) currentRunError() error {
	s.runErrorMu.Lock()
	defer s.runErrorMu.Unlock()
	return s.runError
}

func min(a int, b int) int {
	if a < b {
		return a
	}
	return b
}

func max(a int, b int) int {
	if a > b {
		return a
	}
	return b
}
