package pullmetrics

import "sync/atomic"

const MaximumBatch = 15

type Snapshot struct {
	EmptyResponses       uint64
	Batches              [MaximumBatch + 1]uint64
	AboveProtocolMaximum bool
}

type Recorder struct {
	emptyResponses atomic.Uint64
	batches        [MaximumBatch + 1]atomic.Uint64
	aboveProtocol  atomic.Bool
}

func NewRecorder() *Recorder {
	return &Recorder{}
}

func (r *Recorder) Observe(size int) {
	if size == 0 {
		r.emptyResponses.Add(1)
		return
	}
	if size < 0 || size > MaximumBatch {
		r.aboveProtocol.Store(true)
		return
	}
	r.batches[size].Add(1)
}

func (r *Recorder) Snapshot() Snapshot {
	snapshot := Snapshot{
		EmptyResponses:       r.emptyResponses.Load(),
		AboveProtocolMaximum: r.aboveProtocol.Load(),
	}
	for size := 1; size <= MaximumBatch; size++ {
		snapshot.Batches[size] = r.batches[size].Load()
	}
	return snapshot
}
