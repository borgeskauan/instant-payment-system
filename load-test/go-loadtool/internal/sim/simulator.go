package sim

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/connectivity"
	"google.golang.org/grpc/credentials"

	"instant-payment-system/load-test/go-loadtool/internal/config"
	"instant-payment-system/load-test/go-loadtool/internal/events"
	"instant-payment-system/load-test/go-loadtool/internal/gen/notificationpb"
	"instant-payment-system/load-test/go-loadtool/internal/ids"
	"instant-payment-system/load-test/go-loadtool/internal/payload"
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
	TargetTxRate                  int
	Warmup                        time.Duration
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
}

type statusJob struct {
	receiverISPB string
	endToEndID   string
	scenarioName string
}

type simulator struct {
	cfg                        Config
	runID                      string
	httpClients                map[string]*http.Client
	startWriter                *events.StartWriter
	eventWriter                *events.NotificationWriter
	replayWriter               *events.ReplayWriter
	statusStartWriter          *events.StatusStartWriter
	replayScheduler            *replayScheduler
	startMu                    sync.Mutex
	eventMu                    sync.Mutex
	replayMu                   sync.Mutex
	statusStartMu              sync.Mutex
	pacs002SelectorMu          sync.Mutex
	statusQueuedMu             sync.Mutex
	transferScenariosMu        sync.RWMutex
	runErrorMu                 sync.Mutex
	runError                   error
	buildPacs008Func           func(string, string, string, int64) []byte
	buildPacs002Func           func(string) []byte
	sendPacs002Func            func(context.Context, statusJob)
	openNotificationStreamFunc func(context.Context, string) (notificationStreamClient, func() error, error)
	statusJobs                 chan statusJob
	statusQueuedIDs            map[string]struct{}
	transferScenarios          map[string]string
	pacs002ReplaySelector      *replaySelector
	generationEndedAt          time.Time
	replayDeadlineAt           time.Time
	started                    atomic.Uint64
	accepted                   atomic.Uint64
	pacs002Sent                atomic.Uint64
	notifications              atomic.Uint64
	statusJobsQueued           atomic.Uint64
	replaysScheduled           atomic.Uint64
	replaysSent                atomic.Uint64
	replaysAccepted            atomic.Uint64
}

type notificationStreamClient interface {
	Send(*notificationpb.ClientMessage) error
	Recv() (*notificationpb.Notification, error)
	CloseSend() error
}

type notificationStreamSession struct {
	ispb         string
	receiverRole bool
	stream       notificationStreamClient
	close        func() error
}

type grpcReadyConn interface {
	Connect()
	GetState() connectivity.State
	WaitForStateChange(context.Context, connectivity.State) bool
}

func Run(cfg Config) error {
	if cfg.TargetTxRate <= 0 {
		return fmt.Errorf("rate must be positive")
	}
	if _, err := maximumGeneratedTransfers(cfg.TargetTxRate, cfg.Warmup, cfg.Duration); err != nil {
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

	startWriter, err := events.NewStartWriter(filepath.Join(cfg.OutputDir, "starts.csv"))
	if err != nil {
		return err
	}
	defer startWriter.Close()

	eventWriter, err := events.NewNotificationWriter(filepath.Join(cfg.OutputDir, "events.csv"))
	if err != nil {
		return err
	}
	defer eventWriter.Close()

	replayWriter, err := events.NewReplayWriter(filepath.Join(cfg.OutputDir, "replays.csv"))
	if err != nil {
		return err
	}
	defer replayWriter.Close()

	statusStartWriter, err := events.NewStatusStartWriter(filepath.Join(cfg.OutputDir, "status-starts.csv"))
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
	httpClients, err := newHTTPClients(cfg, pairs)
	if err != nil {
		return err
	}
	defer closeHTTPClients(httpClients)

	s := &simulator{
		cfg:                   cfg,
		runID:                 fmt.Sprintf("go-%d", time.Now().UnixNano()),
		httpClients:           httpClients,
		startWriter:           startWriter,
		eventWriter:           eventWriter,
		replayWriter:          replayWriter,
		statusStartWriter:     statusStartWriter,
		statusJobs:            make(chan statusJob, statusQueueCapacity(cfg.TargetTxRate)),
		statusQueuedIDs:       make(map[string]struct{}),
		transferScenarios:     make(map[string]string),
		pacs002ReplaySelector: pacs002ReplaySelector,
	}
	s.sendPacs002Func = s.sendPacs002

	rootCtx, cancel := context.WithCancel(context.Background())
	defer cancel()

	logPhase("connecting notification streams: streams=%d", len(pairs)*2)
	notificationCtx, stopNotifications := context.WithCancel(rootCtx)
	sessions, err := s.openNotificationStreams(notificationCtx, pairs)
	if err != nil {
		stopNotifications()
		return err
	}

	var streams sync.WaitGroup
	for _, session := range sessions {
		streams.Add(1)
		go s.consumeNotificationStream(notificationCtx, &streams, session)
	}

	// Give streams a short window to connect before the generator starts.
	time.Sleep(2 * time.Second)
	logPhase("notification streams warmup finished")

	windowPath := cfg.RunWindowPath
	if windowPath == "" {
		windowPath = filepath.Join(cfg.OutputDir, "run-window.json")
	}
	windowDocument := runwindow.New(cfg.ProfileName, time.Now(), cfg.Warmup, cfg.Duration, cfg.Drain, cfg.Replay)
	if err := runwindow.Write(windowPath, windowDocument); err != nil {
		stopNotifications()
		closeNotificationSessions(sessions)
		streams.Wait()
		return err
	}
	s.generationEndedAt = windowDocument.Window.GenerationEndedAt
	s.replayDeadlineAt = windowDocument.Window.ReplayDeadlineAt
	experimentCtx, stopExperiment := context.WithDeadline(rootCtx, s.replayDeadlineAt)
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
		replayRate := int(math.Ceil(float64(cfg.TargetTxRate) * replayShare))
		replayWorkerCount := workerCountForTargetRate(max(1, replayRate))
		s.replayScheduler = newReplayScheduler(experimentCtx, replayWorkerCount)
		s.startReplayWorkers(experimentCtx, &replayWorkers, s.replayScheduler.Ready(), replayWorkerCount)
		if cfg.Replay.Pacs008 != nil {
			logPhase("pacs.008 replay enabled: share=%.2f delay=%s workers=%d", cfg.Replay.Pacs008.Share, cfg.Replay.Pacs008.Delay, replayWorkerCount)
		}
		if cfg.Replay.Pacs002 != nil {
			logPhase("pacs.002 replay enabled: share=%.2f delay=%s workers=%d", cfg.Replay.Pacs002.Share, cfg.Replay.Pacs002.Delay, replayWorkerCount)
		}
	}

	statusWorkerCount := workerCountForTargetRate(cfg.TargetTxRate)
	var statusWorkers sync.WaitGroup
	s.startStatusWorkers(experimentCtx, &statusWorkers, s.statusJobs, statusWorkerCount)

	jobs := make(chan transferJob, cfg.TargetTxRate*2)
	var workers sync.WaitGroup
	workerCount := workerCountForTargetRate(cfg.TargetTxRate)
	if cfg.Warmup > 0 {
		logPhase("starting warmup plus active load: warmup_rate=%d/s active_rate=%d/s warmup=%s active=%s workers=%d status_workers=%d", warmupRate(cfg.TargetTxRate), cfg.TargetTxRate, cfg.Warmup, cfg.Duration, workerCount, statusWorkerCount)
	} else {
		logPhase("starting active load: rate=%d/s duration=%s workers=%d status_workers=%d", cfg.TargetTxRate, cfg.Duration, workerCount, statusWorkerCount)
	}
	for range workerCount {
		workers.Add(1)
		go s.transferWorker(experimentCtx, &workers, jobs)
	}

	s.generate(experimentCtx, jobs, planner, selector, windowDocument.Window)
	close(jobs)
	logPhase("load generation finished; waiting for in-flight HTTP requests")
	workers.Wait()
	logPhase("original HTTP workers finished; waiting until fixed replay deadline: deadline=%s", s.replayDeadlineAt.Format(time.RFC3339Nano))
	<-experimentCtx.Done()
	logPhase("replay deadline reached; closing notification streams")
	cancel()
	stopNotifications()
	closeNotificationSessions(sessions)
	streams.Wait()
	logPhase("notification streams closed")
	close(s.statusJobs)
	statusWorkers.Wait()
	logPhase("status workers finished")
	if s.replayScheduler != nil {
		s.replayScheduler.Close()
		s.replayScheduler.Wait()
		replayWorkers.Wait()
		logPhase("replay workers finished")
	}

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
				Transport: &http.Transport{
					MaxIdleConns:        4096,
					MaxIdleConnsPerHost: 4096,
					MaxConnsPerHost:     4096,
					IdleConnTimeout:     90 * time.Second,
					TLSClientConfig: &tls.Config{
						MinVersion:   tls.VersionTLS12,
						ServerName:   cfg.CentralTransferServerName,
						RootCAs:      rootCAs,
						Certificates: []tls.Certificate{certificate},
					},
				},
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

func workerCountForTargetRate(targetRate int) int {
	return max(16, min(512, targetRate/2))
}

func statusQueueCapacity(targetRate int) int {
	return max(1024, targetRate*4)
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

func (s *simulator) generate(ctx context.Context, jobs chan<- transferJob, planner *workloadPlanner, selector *replaySelector, window runwindow.Window) {
	start := window.GenerationStartedAt
	next := start
	for seq := uint64(0); ; seq++ {
		if !waitUntil(ctx, next, window.GenerationEndedAt) {
			return
		}
		now := time.Now()
		if !canStartOriginal(now, window.GenerationEndedAt) {
			return
		}
		elapsed := now.Sub(start)
		rate := loadRateForElapsed(elapsed, s.cfg.Warmup, s.cfg.TargetTxRate)
		next = next.Add(time.Second / time.Duration(rate))

		job := s.transferJobForSequence(seq, planner.Next())
		if selector != nil {
			job.ReplaySelected = selector.Next()
		}

		select {
		case jobs <- job:
		case <-ctx.Done():
			return
		case <-time.After(time.Until(window.GenerationEndedAt)):
			return
		}
	}
}

func waitUntil(ctx context.Context, target time.Time, end time.Time) bool {
	if !target.Before(end) {
		return false
	}
	wait := time.Until(target)
	if wait <= 0 {
		return true
	}
	timer := time.NewTimer(wait)
	defer stopTimer(timer)
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}

func canStartOriginal(now time.Time, generationEnd time.Time) bool {
	return now.Before(generationEnd)
}

func (s *simulator) transferJobForSequence(seq uint64, planned plannedTransfer) transferJob {
	return transferJob{
		ID:           ids.TransactionID(s.runID, seq),
		Pair:         planned.Pair,
		Created:      time.Now().UnixNano(),
		Amount:       planned.Amount,
		ScenarioName: planned.ScenarioName,
	}
}

func loadRateForElapsed(elapsed time.Duration, warmup time.Duration, targetRate int) int {
	if warmup <= 0 || elapsed >= warmup {
		return targetRate
	}
	return warmupRate(targetRate)
}

func warmupRate(targetRate int) int {
	rate := targetRate / 2
	if rate < 1 {
		return 1
	}
	return rate
}

func (s *simulator) transferWorker(ctx context.Context, wg *sync.WaitGroup, jobs <-chan transferJob) {
	defer wg.Done()
	for {
		select {
		case <-ctx.Done():
			return
		case job, ok := <-jobs:
			if !ok {
				return
			}
			if s.generationEndedAt.IsZero() || canStartOriginal(time.Now(), s.generationEndedAt) {
				s.sendPacs008(ctx, job)
			}
		}
	}
}

func (s *simulator) sendPacs008(ctx context.Context, job transferJob) {
	buildPayload := payload.Pacs008
	if s.buildPacs008Func != nil {
		buildPayload = s.buildPacs008Func
	}
	body := buildPayload(job.ID, job.Pair.Payer, job.Pair.Receiver, job.Amount)
	startedAtTime := time.Now()
	startedAt := startedAtTime.UnixNano()
	if job.ReplaySelected {
		if s.replayScheduler == nil || s.cfg.Replay.Pacs008 == nil {
			s.recordRunError(fmt.Errorf("selected pacs.008 replay %q has no configured scheduler", job.ID))
		} else {
			err := s.replayScheduler.Schedule(replayJob{
				endToEndID:   job.ID,
				senderISPB:   job.Pair.Payer,
				scenarioName: job.ScenarioName,
				messageType:  events.MessagePacs008,
				endpoint:     "/transfer",
				body:         body,
				dueAt:        startedAtTime.Add(s.cfg.Replay.Pacs008.Delay),
			})
			if err != nil {
				s.recordRunError(fmt.Errorf("schedule pacs.008 replay %q: %w", job.ID, err))
			} else {
				s.replaysScheduled.Add(1)
			}
		}
	}
	s.transferScenariosMu.Lock()
	if s.transferScenarios == nil {
		s.transferScenarios = make(map[string]string)
	}
	s.transferScenarios[job.ID] = job.ScenarioName
	s.transferScenariosMu.Unlock()
	status := s.post(ctx, job.Pair.Payer, fmt.Sprintf("%s/transfer", s.cfg.BaseURL), body)
	doneAt := time.Now().UnixNano()
	s.started.Add(1)
	if status >= 200 && status < 300 {
		s.accepted.Add(1)
	}
	s.writeStart(events.Start{
		EndToEndID:            job.ID,
		PayerISPB:             job.Pair.Payer,
		ReceiverISPB:          job.Pair.Receiver,
		CreatedAtNS:           job.Created,
		RequestStartedAtNS:    startedAt,
		RequestDoneAtNS:       doneAt,
		HTTPStatus:            status,
		ScenarioName:          job.ScenarioName,
		Pacs008ReplaySelected: job.ReplaySelected,
	})
}

func (s *simulator) sendReplay(ctx context.Context, job replayJob) {
	if !time.Now().Before(s.replayDeadlineAt) && !s.replayDeadlineAt.IsZero() {
		return
	}
	startedAt := time.Now().UnixNano()
	status := s.post(ctx, job.senderISPB, s.cfg.BaseURL+job.endpoint, job.body)
	doneAt := time.Now().UnixNano()
	s.replaysSent.Add(1)
	if status >= 200 && status < 300 {
		s.replaysAccepted.Add(1)
	}
	s.writeReplay(events.Replay{
		EndToEndID:         job.endToEndID,
		SenderISPB:         job.senderISPB,
		ScenarioName:       job.scenarioName,
		MessageType:        job.messageType,
		RequestStartedAtNS: startedAt,
		RequestDoneAtNS:    doneAt,
		HTTPStatus:         status,
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
	if !time.Now().Before(s.replayDeadlineAt) && !s.replayDeadlineAt.IsZero() {
		return
	}
	buildPayload := payload.Pacs002
	if s.buildPacs002Func != nil {
		buildPayload = s.buildPacs002Func
	}
	body := buildPayload(job.endToEndID)
	selected := false
	if s.pacs002ReplaySelector != nil {
		s.pacs002SelectorMu.Lock()
		selected = s.pacs002ReplaySelector.Next()
		s.pacs002SelectorMu.Unlock()
	}
	startedAtTime := time.Now()
	if selected {
		if s.replayScheduler == nil || s.cfg.Replay.Pacs002 == nil {
			s.recordRunError(fmt.Errorf("selected pacs.002 replay %q has no configured scheduler", job.endToEndID))
		} else if err := s.replayScheduler.Schedule(replayJob{
			endToEndID:   job.endToEndID,
			senderISPB:   job.receiverISPB,
			scenarioName: job.scenarioName,
			messageType:  events.MessagePacs002,
			endpoint:     "/transfer/status",
			body:         body,
			dueAt:        startedAtTime.Add(s.cfg.Replay.Pacs002.Delay),
		}); err != nil {
			s.recordRunError(fmt.Errorf("schedule pacs.002 replay %q: %w", job.endToEndID, err))
		} else {
			s.replaysScheduled.Add(1)
		}
	}
	status := s.post(
		ctx,
		job.receiverISPB,
		fmt.Sprintf("%s/transfer/status", s.cfg.BaseURL),
		body,
	)
	doneAt := time.Now().UnixNano()
	s.writeStatusStart(events.StatusStart{
		EndToEndID:            job.endToEndID,
		SenderISPB:            job.receiverISPB,
		ScenarioName:          job.scenarioName,
		RequestStartedAtNS:    startedAtTime.UnixNano(),
		RequestDoneAtNS:       doneAt,
		HTTPStatus:            status,
		Pacs002ReplaySelected: selected,
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
	s.statusQueuedMu.Lock()
	if _, exists := s.statusQueuedIDs[endToEndID]; exists {
		s.statusQueuedMu.Unlock()
		return
	}
	s.statusQueuedIDs[endToEndID] = struct{}{}
	s.statusQueuedMu.Unlock()
	s.transferScenariosMu.RLock()
	scenarioName, exists := s.transferScenarios[endToEndID]
	s.transferScenariosMu.RUnlock()
	if !exists {
		s.recordRunError(fmt.Errorf("pacs.002 original %q has no generated transfer metadata", endToEndID))
		return
	}
	select {
	case s.statusJobs <- statusJob{receiverISPB: receiverISPB, endToEndID: endToEndID, scenarioName: scenarioName}:
		s.statusJobsQueued.Add(1)
	case <-ctx.Done():
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

func (s *simulator) post(ctx context.Context, ispb string, url string, body []byte) int {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return 0
	}
	req.Header.Set("Content-Type", "application/octet-stream")
	client, exists := s.httpClients[ispb]
	if !exists {
		return 0
	}
	resp, err := client.Do(req)
	if err != nil {
		return 0
	}
	_, _ = io.Copy(io.Discard, resp.Body)
	_ = resp.Body.Close()
	return resp.StatusCode
}

func (s *simulator) openNotificationStreams(
	ctx context.Context,
	pairs []ids.Pair,
) ([]notificationStreamSession, error) {
	specs := notificationStreamSpecs(pairs)
	sessions := make([]notificationStreamSession, 0, len(specs))

	for _, spec := range specs {
		open := s.openNotificationStream
		if s.openNotificationStreamFunc != nil {
			open = s.openNotificationStreamFunc
		}
		stream, closeFunc, err := open(ctx, spec.ispb)
		if err != nil {
			closeNotificationSessions(sessions)
			return nil, fmt.Errorf("open notification stream for ISPB %s: %w", spec.ispb, err)
		}
		sessions = append(sessions, notificationStreamSession{
			ispb:         spec.ispb,
			receiverRole: spec.receiverRole,
			stream:       stream,
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

func (s *simulator) openNotificationStream(
	ctx context.Context,
	ispb string,
) (notificationStreamClient, func() error, error) {
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
	stream, err := client.StreamNotifications(ctx)
	if err != nil {
		_ = conn.Close()
		return nil, nil, err
	}

	closeFunc := func() error {
		_ = stream.CloseSend()
		return conn.Close()
	}
	return stream, closeFunc, nil
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

func closeNotificationSessions(sessions []notificationStreamSession) {
	for _, session := range sessions {
		if session.close != nil {
			_ = session.close()
		}
	}
}

func (s *simulator) consumeNotificationStream(ctx context.Context, wg *sync.WaitGroup, session notificationStreamSession) {
	defer wg.Done()
	for {
		msg, err := session.stream.Recv()
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			fmt.Fprintf(os.Stderr, "stream %s recv failed: %v\n", session.ispb, err)
			return
		}
		notifications, err := payload.ExtractNotifications(msg.Payload)
		if err != nil {
			continue
		}
		s.notifications.Add(uint64(len(notifications)))
		for _, notification := range notifications {
			switch notification.Kind {
			case payload.KindPacs008:
				s.writeNotification(events.Notification{
					EndToEndID:   notification.EndToEndID,
					ISPB:         session.ispb,
					EventType:    events.EventPacs008Received,
					ReceivedAtNS: time.Now().UnixNano(),
				})
				if session.receiverRole {
					s.enqueuePacs002(ctx, session.ispb, notification.EndToEndID)
				}
			case payload.KindPacs002:
				s.writeNotification(events.Notification{
					EndToEndID:   notification.EndToEndID,
					ISPB:         session.ispb,
					EventType:    events.EventPacs002Received,
					ReceivedAtNS: time.Now().UnixNano(),
					StatusCode:   notification.StatusCode,
					ReasonCodes:  notification.ReasonCodes,
				})
			}
		}
		if deliveryID := msg.GetDeliveryId(); deliveryID != "" {
			_ = session.stream.Send(&notificationpb.ClientMessage{
				Message: &notificationpb.ClientMessage_Ack{
					Ack: &notificationpb.Ack{DeliveryId: deliveryID},
				},
			})
		}
	}
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
