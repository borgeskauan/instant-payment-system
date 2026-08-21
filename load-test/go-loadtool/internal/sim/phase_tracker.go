package sim

import (
	"context"
	"fmt"
	"sync"
)

type phaseTracker struct {
	mu               sync.Mutex
	pending          int
	generationClosed bool
	completed        bool
	failure          error
	changed          chan struct{}
}

func newPhaseTracker() *phaseTracker {
	return &phaseTracker{changed: make(chan struct{}, 1)}
}

func (tracker *phaseTracker) Add() error {
	tracker.mu.Lock()
	defer tracker.mu.Unlock()
	if tracker.completed {
		return fmt.Errorf("phase tracker already completed")
	}
	tracker.pending++
	tracker.signal()
	return nil
}

func (tracker *phaseTracker) Done() error {
	tracker.mu.Lock()
	defer tracker.mu.Unlock()
	if tracker.pending == 0 {
		return fmt.Errorf("phase tracker has no pending work")
	}
	tracker.pending--
	tracker.signal()
	return nil
}

func (tracker *phaseTracker) Fail(err error) {
	if err == nil {
		return
	}
	tracker.mu.Lock()
	defer tracker.mu.Unlock()
	if tracker.failure == nil {
		tracker.failure = err
	}
	tracker.signal()
}

func (tracker *phaseTracker) CloseGeneration() {
	tracker.mu.Lock()
	defer tracker.mu.Unlock()
	tracker.generationClosed = true
	tracker.signal()
}

func (tracker *phaseTracker) Wait(ctx context.Context) error {
	for {
		tracker.mu.Lock()
		if tracker.failure != nil {
			err := tracker.failure
			tracker.mu.Unlock()
			return err
		}
		if tracker.generationClosed && tracker.pending == 0 {
			tracker.completed = true
			tracker.mu.Unlock()
			return nil
		}
		tracker.mu.Unlock()

		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-tracker.changed:
		}
	}
}

func (tracker *phaseTracker) signal() {
	select {
	case tracker.changed <- struct{}{}:
	default:
	}
}
