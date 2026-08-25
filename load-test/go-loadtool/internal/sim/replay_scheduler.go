package sim

import (
	"container/heap"
	"context"
	"fmt"
	"sync"
	"time"
)

type replayJob struct {
	endToEndID     string
	senderISPB     string
	scenarioName   string
	messageType    string
	endpoint       string
	body           []byte
	dueAt          time.Time
	requestTimeout time.Duration
	tracker        *phaseTracker
}

type replayJobHeap []replayJob

func (jobs replayJobHeap) Len() int           { return len(jobs) }
func (jobs replayJobHeap) Less(i, j int) bool { return jobs[i].dueAt.Before(jobs[j].dueAt) }
func (jobs replayJobHeap) Swap(i, j int)      { jobs[i], jobs[j] = jobs[j], jobs[i] }
func (jobs *replayJobHeap) Push(value any)    { *jobs = append(*jobs, value.(replayJob)) }
func (jobs *replayJobHeap) Pop() any {
	old := *jobs
	last := len(old) - 1
	value := old[last]
	*jobs = old[:last]
	return value
}

type replayScheduler struct {
	ctx       context.Context
	mu        sync.Mutex
	jobs      replayJobHeap
	closed    bool
	wake      chan struct{}
	ready     chan replayJob
	wait      sync.WaitGroup
	closeOnce sync.Once
}

func newReplayScheduler(ctx context.Context, readyCapacity int) *replayScheduler {
	if readyCapacity < 1 {
		readyCapacity = 1
	}
	scheduler := &replayScheduler{
		ctx:   ctx,
		wake:  make(chan struct{}, 1),
		ready: make(chan replayJob, readyCapacity),
	}
	heap.Init(&scheduler.jobs)
	scheduler.wait.Add(1)
	go scheduler.run()
	return scheduler
}

func (scheduler *replayScheduler) Schedule(job replayJob) error {
	scheduler.mu.Lock()
	if scheduler.closed {
		scheduler.mu.Unlock()
		return fmt.Errorf("replay scheduler is closed")
	}
	wake := len(scheduler.jobs) == 0 || job.dueAt.Before(scheduler.jobs[0].dueAt)
	heap.Push(&scheduler.jobs, job)
	scheduler.mu.Unlock()
	if wake {
		scheduler.signal()
	}
	return nil
}

func (scheduler *replayScheduler) Ready() <-chan replayJob {
	return scheduler.ready
}

func (scheduler *replayScheduler) Close() {
	scheduler.closeOnce.Do(func() {
		scheduler.mu.Lock()
		scheduler.closed = true
		scheduler.mu.Unlock()
		scheduler.signal()
	})
}

func (scheduler *replayScheduler) Wait() {
	scheduler.wait.Wait()
}

func (scheduler *replayScheduler) signal() {
	select {
	case scheduler.wake <- struct{}{}:
	default:
	}
}

func (scheduler *replayScheduler) run() {
	defer scheduler.wait.Done()
	defer close(scheduler.ready)
	for {
		job, hasJob, closed := scheduler.next()
		if !hasJob {
			if closed {
				return
			}
			select {
			case <-scheduler.ctx.Done():
				return
			case <-scheduler.wake:
			}
			continue
		}

		wait := time.Until(job.dueAt)
		if wait > 0 {
			timer := time.NewTimer(wait)
			select {
			case <-scheduler.ctx.Done():
				stopTimer(timer)
				return
			case <-scheduler.wake:
				stopTimer(timer)
				continue
			case <-timer.C:
			}
		}

		job, ok := scheduler.popDue()
		if !ok {
			continue
		}
		select {
		case <-scheduler.ctx.Done():
			return
		case scheduler.ready <- job:
		}
	}
}

func (scheduler *replayScheduler) next() (replayJob, bool, bool) {
	scheduler.mu.Lock()
	defer scheduler.mu.Unlock()
	if len(scheduler.jobs) == 0 {
		return replayJob{}, false, scheduler.closed
	}
	return scheduler.jobs[0], true, scheduler.closed
}

func (scheduler *replayScheduler) popDue() (replayJob, bool) {
	scheduler.mu.Lock()
	defer scheduler.mu.Unlock()
	if len(scheduler.jobs) == 0 || time.Until(scheduler.jobs[0].dueAt) > 0 {
		return replayJob{}, false
	}
	return heap.Pop(&scheduler.jobs).(replayJob), true
}

func stopTimer(timer *time.Timer) {
	if !timer.Stop() {
		select {
		case <-timer.C:
		default:
		}
	}
}

func resetTimer(timer *time.Timer, duration time.Duration) {
	stopTimer(timer)
	timer.Reset(duration)
}
