package sim

import (
	"errors"
	"fmt"
	"path/filepath"
	"sync"
	"sync/atomic"

	"instant-payment-system/load-test/go-loadtool/internal/events"
)

const eventQueueHeadroomSeconds = 10

type typedEventWriter[T any] interface {
	Write(T) error
	Close() error
}

type eventRecorderWriters struct {
	starts        typedEventWriter[events.Start]
	notifications typedEventWriter[events.Notification]
	replays       typedEventWriter[events.Replay]
	statusStarts  typedEventWriter[events.StatusStart]
}

type eventRecorder struct {
	starts        *queuedEventWriter[events.Start]
	notifications *queuedEventWriter[events.Notification]
	replays       *queuedEventWriter[events.Replay]
	statusStarts  *queuedEventWriter[events.StatusStart]
	closeOnce     sync.Once
	closeErr      error
}

type queuedEventWriter[T any] struct {
	name      string
	writer    typedEventWriter[T]
	queue     chan T
	done      chan struct{}
	onFailure func(error)
	failOnce  sync.Once
	failure   atomic.Pointer[eventRecorderFailure]
}

type eventRecorderFailure struct {
	err error
}

func eventQueueCapacity(rate int) int {
	return max(1024, rate*eventQueueHeadroomSeconds)
}

func newEventRecorder(outputDir string, queueCapacity int, onFailure func(error)) (*eventRecorder, error) {
	startWriter, err := events.NewStartWriter(filepath.Join(outputDir, "pacs008-starts.csv"))
	if err != nil {
		return nil, err
	}
	notificationWriter, err := events.NewNotificationWriter(filepath.Join(outputDir, "notifications.csv"))
	if err != nil {
		_ = startWriter.Close()
		return nil, err
	}
	replayWriter, err := events.NewReplayWriter(filepath.Join(outputDir, "replays.csv"))
	if err != nil {
		_ = startWriter.Close()
		_ = notificationWriter.Close()
		return nil, err
	}
	statusStartWriter, err := events.NewStatusStartWriter(filepath.Join(outputDir, "pacs002-starts.csv"))
	if err != nil {
		_ = startWriter.Close()
		_ = notificationWriter.Close()
		_ = replayWriter.Close()
		return nil, err
	}

	return newEventRecorderWithWriters(eventRecorderWriters{
		starts:        startWriter,
		notifications: notificationWriter,
		replays:       replayWriter,
		statusStarts:  statusStartWriter,
	}, queueCapacity, onFailure), nil
}

func newEventRecorderWithWriters(writers eventRecorderWriters, queueCapacity int, onFailure func(error)) *eventRecorder {
	return &eventRecorder{
		starts:        newQueuedEventWriter("pacs.008", writers.starts, queueCapacity, onFailure),
		notifications: newQueuedEventWriter("notification", writers.notifications, queueCapacity, onFailure),
		replays:       newQueuedEventWriter("replay", writers.replays, queueCapacity, onFailure),
		statusStarts:  newQueuedEventWriter("pacs.002", writers.statusStarts, queueCapacity, onFailure),
	}
}

func newQueuedEventWriter[T any](name string, writer typedEventWriter[T], queueCapacity int, onFailure func(error)) *queuedEventWriter[T] {
	queued := &queuedEventWriter[T]{
		name:      name,
		writer:    writer,
		queue:     make(chan T, queueCapacity),
		done:      make(chan struct{}),
		onFailure: onFailure,
	}
	go queued.run()
	return queued
}

func (r *eventRecorder) RecordStart(row events.Start) error {
	return r.starts.Record(row)
}

func (r *eventRecorder) RecordNotification(row events.Notification) error {
	return r.notifications.Record(row)
}

func (r *eventRecorder) RecordReplay(row events.Replay) error {
	return r.replays.Record(row)
}

func (r *eventRecorder) RecordStatusStart(row events.StatusStart) error {
	return r.statusStarts.Record(row)
}

func (r *eventRecorder) Close() error {
	r.closeOnce.Do(func() {
		close(r.starts.queue)
		close(r.notifications.queue)
		close(r.replays.queue)
		close(r.statusStarts.queue)

		<-r.starts.done
		<-r.notifications.done
		<-r.replays.done
		<-r.statusStarts.done

		r.closeErr = errors.Join(
			r.starts.currentError(),
			r.notifications.currentError(),
			r.replays.currentError(),
			r.statusStarts.currentError(),
		)
	})
	return r.closeErr
}

func (w *queuedEventWriter[T]) Record(row T) error {
	if err := w.currentError(); err != nil {
		return err
	}
	select {
	case w.queue <- row:
		return nil
	default:
		err := fmt.Errorf("%s event queue is full (capacity=%d)", w.name, cap(w.queue))
		w.fail(err)
		return err
	}
}

func (w *queuedEventWriter[T]) run() {
	defer close(w.done)
	writeFailed := false
	for row := range w.queue {
		if writeFailed {
			continue
		}
		if err := w.writer.Write(row); err != nil {
			w.fail(fmt.Errorf("write %s event: %w", w.name, err))
			writeFailed = true
		}
	}
	if err := w.writer.Close(); err != nil {
		w.fail(fmt.Errorf("close %s event writer: %w", w.name, err))
	}
}

func (w *queuedEventWriter[T]) fail(err error) {
	w.failOnce.Do(func() {
		w.failure.Store(&eventRecorderFailure{err: err})
		if w.onFailure != nil {
			w.onFailure(err)
		}
	})
}

func (w *queuedEventWriter[T]) currentError() error {
	failure := w.failure.Load()
	if failure == nil {
		return nil
	}
	return failure.err
}
