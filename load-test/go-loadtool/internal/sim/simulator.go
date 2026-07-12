package sim

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"

	"instant-payment-system/load-test/go-loadtool/internal/events"
	"instant-payment-system/load-test/go-loadtool/internal/gen/notificationpb"
	"instant-payment-system/load-test/go-loadtool/internal/ids"
	"instant-payment-system/load-test/go-loadtool/internal/payload"
)

type Config struct {
	BaseURL        string
	GatewayAddress string
	TargetTxRate   int
	Warmup         time.Duration
	Duration       time.Duration
	Drain          time.Duration
	HotPSPs        int
	ColdPSPs       int
	HotShare       float64
	OutputDir      string
}

type transferJob struct {
	ID      string
	Pair    ids.Pair
	Created int64
	Amount  int64
}

type statusJob struct {
	receiverISPB string
	endToEndID   string
}

type simulator struct {
	cfg              Config
	runID            string
	httpClient       *http.Client
	grpcConn         *grpc.ClientConn
	grpcClient       notificationpb.NotificationGatewayClient
	startWriter      *events.StartWriter
	eventWriter      *events.NotificationWriter
	startMu          sync.Mutex
	eventMu          sync.Mutex
	sendPacs002Func  func(context.Context, string, string)
	statusJobs       chan statusJob
	started          atomic.Uint64
	accepted         atomic.Uint64
	pacs002Sent      atomic.Uint64
	notifications    atomic.Uint64
	statusJobsQueued atomic.Uint64
}

func Run(cfg Config) error {
	if cfg.TargetTxRate <= 0 {
		return fmt.Errorf("rate must be positive")
	}
	if cfg.HotPSPs <= 0 || cfg.ColdPSPs <= 0 {
		return fmt.Errorf("hot and cold PSP counts must be positive")
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

	conn, err := grpc.NewClient(cfg.GatewayAddress, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		return err
	}
	defer conn.Close()

	s := &simulator{
		cfg:         cfg,
		runID:       fmt.Sprintf("go-%d", time.Now().UnixNano()),
		httpClient:  newHTTPClient(),
		grpcConn:    conn,
		grpcClient:  notificationpb.NewNotificationGatewayClient(conn),
		startWriter: startWriter,
		eventWriter: eventWriter,
		statusJobs:  make(chan statusJob, statusQueueCapacity(cfg.TargetTxRate)),
	}
	s.sendPacs002Func = s.sendPacs002

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	statusWorkerCount := workerCountForTargetRate(cfg.TargetTxRate)
	var statusWorkers sync.WaitGroup
	s.startStatusWorkers(ctx, &statusWorkers, s.statusJobs, statusWorkerCount)

	pairs := buildPairs(cfg.HotPSPs + cfg.ColdPSPs)
	logPhase("connecting notification streams: streams=%d", len(pairs)*2)
	var streams sync.WaitGroup
	for _, pair := range pairs {
		streams.Add(2)
		go s.streamNotifications(ctx, &streams, pair.Receiver, true)
		go s.streamNotifications(ctx, &streams, pair.Payer, false)
	}

	// Give streams a short window to connect before the generator starts.
	time.Sleep(2 * time.Second)
	logPhase("notification streams warmup finished")

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
		go s.transferWorker(ctx, &workers, jobs)
	}

	s.generate(ctx, jobs, pairs)
	close(jobs)
	logPhase("load generation finished; waiting for in-flight HTTP requests")
	workers.Wait()

	logPhase("HTTP workers finished; entering drain: drain=%s", cfg.Drain)
	time.Sleep(cfg.Drain)
	logPhase("drain finished; closing notification streams")
	cancel()
	streams.Wait()
	logPhase("notification streams closed")
	close(s.statusJobs)
	statusWorkers.Wait()
	logPhase("status workers finished")

	fmt.Printf("started=%d accepted=%d notifications=%d pacs002_queued=%d pacs002_sent=%d output=%s\n",
		s.started.Load(),
		s.accepted.Load(),
		s.notifications.Load(),
		s.statusJobsQueued.Load(),
		s.pacs002Sent.Load(),
		cfg.OutputDir,
	)
	return nil
}

func logPhase(format string, args ...any) {
	fmt.Printf("[%s] %s\n", time.Now().Format("2006-01-02 15:04:05"), fmt.Sprintf(format, args...))
}

func newHTTPClient() *http.Client {
	return &http.Client{
		Transport: &http.Transport{
			MaxIdleConns:        4096,
			MaxIdleConnsPerHost: 4096,
			MaxConnsPerHost:     4096,
			IdleConnTimeout:     90 * time.Second,
		},
		Timeout: 5 * time.Second,
	}
}

func workerCountForTargetRate(targetRate int) int {
	return max(16, min(512, targetRate/2))
}

func statusQueueCapacity(targetRate int) int {
	return max(1024, targetRate*4)
}

func buildPairs(count int) []ids.Pair {
	pairs := make([]ids.Pair, 0, count)
	for i := 1; i <= count; i++ {
		pairs = append(pairs, ids.PSPPair(i))
	}
	return pairs
}

func (s *simulator) generate(ctx context.Context, jobs chan<- transferJob, pairs []ids.Pair) {
	start := time.Now()
	next := start
	endAfter := s.cfg.Warmup + s.cfg.Duration
	hotCount := s.cfg.HotPSPs
	coldEvery := int(1 / (1 - s.cfg.HotShare))
	if coldEvery < 2 {
		coldEvery = 2
	}

	for seq := uint64(0); time.Since(start) < endAfter; seq++ {
		if sleep := next.Sub(time.Now()); sleep > 0 {
			time.Sleep(sleep)
		}
		elapsed := time.Since(start)
		rate := loadRateForElapsed(elapsed, s.cfg.Warmup, s.cfg.TargetTxRate)
		next = next.Add(time.Second / time.Duration(rate))

		pairIndex := hotCount + int(seq)%s.cfg.ColdPSPs
		if hotCount > 0 && seq%uint64(coldEvery) != 0 {
			pairIndex = int(seq) % hotCount
		}

		job := transferJob{
			ID:      ids.TransactionID(s.runID, seq),
			Pair:    pairs[pairIndex],
			Created: time.Now().UnixNano(),
			Amount:  100 + int64(seq%99999),
		}

		select {
		case jobs <- job:
		case <-ctx.Done():
			return
		}
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
			s.sendPacs008(ctx, job)
		}
	}
}

func (s *simulator) sendPacs008(ctx context.Context, job transferJob) {
	body := payload.Pacs008(job.ID, job.Pair.Payer, job.Pair.Receiver, job.Amount)
	startedAt := time.Now().UnixNano()
	status := s.post(ctx, fmt.Sprintf("%s/%s/transfer", s.cfg.BaseURL, job.Pair.Payer), body)
	doneAt := time.Now().UnixNano()
	s.started.Add(1)
	if status >= 200 && status < 300 {
		s.accepted.Add(1)
	}
	s.writeStart(events.Start{
		EndToEndID:         job.ID,
		PayerISPB:          job.Pair.Payer,
		ReceiverISPB:       job.Pair.Receiver,
		CreatedAtNS:        job.Created,
		RequestStartedAtNS: startedAt,
		RequestDoneAtNS:    doneAt,
		HTTPStatus:         status,
	})
}

func (s *simulator) sendPacs002(ctx context.Context, receiverISPB string, endToEndID string) {
	body := payload.Pacs002(endToEndID)
	status := s.post(ctx, fmt.Sprintf("%s/%s/transfer/status", s.cfg.BaseURL, receiverISPB), body)
	if status < 200 || status >= 300 {
		return
	}
	s.pacs002Sent.Add(1)
	s.writeNotification(events.Notification{
		EndToEndID:   endToEndID,
		ISPB:         receiverISPB,
		EventType:    events.EventPacs002Sent,
		ReceivedAtNS: time.Now().UnixNano(),
	})
}

func (s *simulator) enqueuePacs002(ctx context.Context, receiverISPB string, endToEndID string) {
	select {
	case s.statusJobs <- statusJob{receiverISPB: receiverISPB, endToEndID: endToEndID}:
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
			for job := range jobs {
				s.sendPacs002Func(ctx, job.receiverISPB, job.endToEndID)
			}
		}()
	}
}

func (s *simulator) post(ctx context.Context, url string, body []byte) int {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return 0
	}
	req.Header.Set("Content-Type", "application/octet-stream")
	resp, err := s.httpClient.Do(req)
	if err != nil {
		return 0
	}
	_, _ = io.Copy(io.Discard, resp.Body)
	_ = resp.Body.Close()
	return resp.StatusCode
}

func (s *simulator) streamNotifications(ctx context.Context, wg *sync.WaitGroup, ispb string, receiverRole bool) {
	defer wg.Done()
	stream, err := s.grpcClient.StreamNotifications(ctx, &notificationpb.StreamRequest{Ispb: ispb})
	if err != nil {
		fmt.Fprintf(os.Stderr, "stream %s failed: %v\n", ispb, err)
		return
	}

	for {
		msg, err := stream.Recv()
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			fmt.Fprintf(os.Stderr, "stream %s recv failed: %v\n", ispb, err)
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
					ISPB:         ispb,
					EventType:    events.EventPacs008Received,
					ReceivedAtNS: time.Now().UnixNano(),
				})
				if receiverRole {
					s.enqueuePacs002(ctx, ispb, notification.EndToEndID)
				}
			case payload.KindPacs002:
				s.writeNotification(events.Notification{
					EndToEndID:   notification.EndToEndID,
					ISPB:         ispb,
					EventType:    events.EventPacs002Received,
					ReceivedAtNS: time.Now().UnixNano(),
				})
			}
		}
	}
}

func (s *simulator) writeStart(row events.Start) {
	s.startMu.Lock()
	defer s.startMu.Unlock()
	if err := s.startWriter.Write(row); err != nil {
		fmt.Fprintf(os.Stderr, "write start failed: %v\n", err)
	}
}

func (s *simulator) writeNotification(row events.Notification) {
	s.eventMu.Lock()
	defer s.eventMu.Unlock()
	if err := s.eventWriter.Write(row); err != nil {
		fmt.Fprintf(os.Stderr, "write notification failed: %v\n", err)
	}
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
