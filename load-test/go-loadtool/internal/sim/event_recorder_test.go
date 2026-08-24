package sim

import (
	"context"
	"errors"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"instant-payment-system/load-test/go-loadtool/internal/events"
)

func TestEventRecorderDrainsEveryEventTypeBeforeClosing(t *testing.T) {
	dir := t.TempDir()
	recorder, err := newEventRecorder(dir, 8, nil)
	if err != nil {
		t.Fatal(err)
	}

	start := events.Start{EndToEndID: "tx-1", PayerISPB: "10000001", ReceiverISPB: "20000001", CreatedAtNS: 1, RequestStartedAtNS: 2, RequestDoneAtNS: 3, HTTPStatus: 200, ScenarioName: "happy-path", Pacs008ReplaySelected: true}
	notification := events.Notification{EndToEndID: "tx-1", ISPB: "10000001", EventType: events.EventPacs002Received, ReceivedAtNS: 4, StatusCode: "RJCT", ReasonCodes: []string{"AM04"}}
	replay := events.Replay{EndToEndID: "tx-1", SenderISPB: "10000001", ScenarioName: "happy-path", MessageType: events.MessagePacs008, RequestStartedAtNS: 5, RequestDoneAtNS: 6, HTTPStatus: 202}
	status := events.StatusStart{EndToEndID: "tx-1", SenderISPB: "20000001", ScenarioName: "happy-path", RequestStartedAtNS: 7, RequestDoneAtNS: 8, HTTPStatus: 200, Pacs002ReplaySelected: true}

	if err := recorder.RecordStart(start); err != nil {
		t.Fatal(err)
	}
	if err := recorder.RecordNotification(notification); err != nil {
		t.Fatal(err)
	}
	if err := recorder.RecordReplay(replay); err != nil {
		t.Fatal(err)
	}
	if err := recorder.RecordStatusStart(status); err != nil {
		t.Fatal(err)
	}
	if err := recorder.Close(); err != nil {
		t.Fatal(err)
	}

	starts, err := events.ReadStarts(filepath.Join(dir, "pacs008-starts.csv"))
	if err != nil {
		t.Fatal(err)
	}
	if len(starts) != 1 || starts[0] != start {
		t.Fatalf("starts = %#v, want %#v", starts, start)
	}
	notifications, err := events.ReadNotifications(filepath.Join(dir, "notifications.csv"))
	if err != nil {
		t.Fatal(err)
	}
	if len(notifications) != 1 || notifications[0].EndToEndID != notification.EndToEndID || notifications[0].StatusCode != notification.StatusCode || len(notifications[0].ReasonCodes) != 1 || notifications[0].ReasonCodes[0] != "AM04" {
		t.Fatalf("notifications = %#v, want %#v", notifications, notification)
	}
	replays, err := events.ReadReplays(filepath.Join(dir, "replays.csv"))
	if err != nil {
		t.Fatal(err)
	}
	if len(replays) != 1 || replays[0] != replay {
		t.Fatalf("replays = %#v, want %#v", replays, replay)
	}
	statuses, err := events.ReadStatusStarts(filepath.Join(dir, "pacs002-starts.csv"))
	if err != nil {
		t.Fatal(err)
	}
	if len(statuses) != 1 || statuses[0] != status {
		t.Fatalf("statuses = %#v, want %#v", statuses, status)
	}
}

func TestEventRecorderFailsImmediatelyWhenAWriterQueueIsFull(t *testing.T) {
	blocked := &blockingEventWriter[events.Start]{
		started: make(chan struct{}),
		release: make(chan struct{}),
	}
	failures := make(chan error, 1)
	recorder := newEventRecorderWithWriters(eventRecorderWriters{
		starts:        blocked,
		notifications: discardEventWriter[events.Notification]{},
		replays:       discardEventWriter[events.Replay]{},
		statusStarts:  discardEventWriter[events.StatusStart]{},
	}, 1, func(err error) {
		failures <- err
	})

	if err := recorder.RecordStart(events.Start{EndToEndID: "tx-1"}); err != nil {
		t.Fatal(err)
	}
	select {
	case <-blocked.started:
	case <-time.After(time.Second):
		t.Fatal("writer did not start")
	}
	if err := recorder.RecordStart(events.Start{EndToEndID: "tx-2"}); err != nil {
		t.Fatal(err)
	}

	startedAt := time.Now()
	err := recorder.RecordStart(events.Start{EndToEndID: "tx-3"})
	if err == nil || !strings.Contains(err.Error(), "pacs.008 event queue is full") {
		t.Fatalf("RecordStart error = %v, want full queue", err)
	}
	if elapsed := time.Since(startedAt); elapsed > 100*time.Millisecond {
		t.Fatalf("full queue blocked for %s", elapsed)
	}
	select {
	case callbackErr := <-failures:
		if !errors.Is(callbackErr, err) && callbackErr.Error() != err.Error() {
			t.Fatalf("callback error = %v, want %v", callbackErr, err)
		}
	case <-time.After(time.Second):
		t.Fatal("queue saturation was not reported")
	}

	close(blocked.release)
	if closeErr := recorder.Close(); closeErr == nil || !strings.Contains(closeErr.Error(), "pacs.008 event queue is full") {
		t.Fatalf("Close error = %v, want queue saturation", closeErr)
	}
}

func TestEventRecorderPropagatesAsynchronousWriterFailure(t *testing.T) {
	want := errors.New("disk failed")
	failures := make(chan error, 1)
	recorder := newEventRecorderWithWriters(eventRecorderWriters{
		starts:        failingEventWriter[events.Start]{err: want},
		notifications: discardEventWriter[events.Notification]{},
		replays:       discardEventWriter[events.Replay]{},
		statusStarts:  discardEventWriter[events.StatusStart]{},
	}, 2, func(err error) {
		failures <- err
	})

	if err := recorder.RecordStart(events.Start{EndToEndID: "tx-1"}); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-failures:
		if !strings.Contains(err.Error(), "write pacs.008 event") || !errors.Is(err, want) {
			t.Fatalf("writer error = %v, want wrapped %v", err, want)
		}
	case <-time.After(time.Second):
		t.Fatal("writer failure was not reported")
	}
	if err := recorder.Close(); err == nil || !errors.Is(err, want) {
		t.Fatalf("Close error = %v, want %v", err, want)
	}
}

func TestSimulatorCancelsTheRunWhenAnEventQueueSaturates(t *testing.T) {
	blocked := &blockingEventWriter[events.Start]{
		started: make(chan struct{}),
		release: make(chan struct{}),
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	s := &simulator{cancelRun: cancel}
	recorder := newEventRecorderWithWriters(eventRecorderWriters{
		starts:        blocked,
		notifications: discardEventWriter[events.Notification]{},
		replays:       discardEventWriter[events.Replay]{},
		statusStarts:  discardEventWriter[events.StatusStart]{},
	}, 1, s.abortRun)
	s.eventRecorder = recorder

	s.writeStart(events.Start{EndToEndID: "tx-1"})
	select {
	case <-blocked.started:
	case <-time.After(time.Second):
		t.Fatal("writer did not start")
	}
	s.writeStart(events.Start{EndToEndID: "tx-2"})
	s.writeStart(events.Start{EndToEndID: "tx-3"})

	select {
	case <-ctx.Done():
	case <-time.After(time.Second):
		t.Fatal("queue saturation did not cancel the run")
	}
	if err := s.currentRunError(); err == nil || !strings.Contains(err.Error(), "pacs.008 event queue is full") {
		t.Fatalf("run error = %v, want full PACS.008 queue", err)
	}

	close(blocked.release)
	_ = recorder.Close()
}

func TestEventQueueCapacityUsesConfiguredRateWithAFloor(t *testing.T) {
	if got := eventQueueCapacity(10); got != 1024 {
		t.Fatalf("eventQueueCapacity(10) = %d, want 1024", got)
	}
	if got := eventQueueCapacity(2100); got != 21000 {
		t.Fatalf("eventQueueCapacity(2100) = %d, want 21000", got)
	}
}

func BenchmarkEventRecorderEnqueue(b *testing.B) {
	recorder := newEventRecorderWithWriters(eventRecorderWriters{
		starts:        discardEventWriter[events.Start]{},
		notifications: discardEventWriter[events.Notification]{},
		replays:       discardEventWriter[events.Replay]{},
		statusStarts:  discardEventWriter[events.StatusStart]{},
	}, eventQueueCapacity(2100), nil)
	row := events.Start{EndToEndID: "tx-1"}
	b.ResetTimer()
	for range b.N {
		if len(recorder.starts.queue) >= cap(recorder.starts.queue)-1 {
			b.StopTimer()
			for len(recorder.starts.queue) > cap(recorder.starts.queue)/2 {
				runtime.Gosched()
			}
			b.StartTimer()
		}
		if err := recorder.RecordStart(row); err != nil {
			b.Fatal(err)
		}
	}
	b.StopTimer()
	if err := recorder.Close(); err != nil {
		b.Fatal(err)
	}
}

type blockingEventWriter[T any] struct {
	started chan struct{}
	release chan struct{}
}

func (w *blockingEventWriter[T]) Write(T) error {
	select {
	case <-w.started:
	default:
		close(w.started)
	}
	<-w.release
	return nil
}

func (*blockingEventWriter[T]) Close() error { return nil }

type discardEventWriter[T any] struct{}

func (discardEventWriter[T]) Write(T) error { return nil }
func (discardEventWriter[T]) Close() error  { return nil }

type failingEventWriter[T any] struct {
	err error
}

func (w failingEventWriter[T]) Write(T) error { return w.err }
func (failingEventWriter[T]) Close() error    { return nil }

func newTestEventRecorder(t *testing.T, dir string) *eventRecorder {
	t.Helper()
	recorder, err := newEventRecorder(dir, 1024, nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := recorder.Close(); err != nil {
			t.Errorf("close event recorder: %v", err)
		}
	})
	return recorder
}
