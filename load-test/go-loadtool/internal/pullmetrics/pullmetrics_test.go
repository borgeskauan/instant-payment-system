package pullmetrics

import "testing"

func TestRecorderSummarizesNonEmptyPullBatchesSeparatelyFromEmptyResponses(t *testing.T) {
	recorder := NewRecorder()
	for _, size := range []int{0, 1, 2, 2, 10} {
		recorder.Observe(size)
	}

	snapshot := recorder.Snapshot()
	if snapshot.EmptyResponses != 1 {
		t.Fatalf("snapshot metadata = %#v", snapshot)
	}
	if snapshot.Batches[1] != 1 || snapshot.Batches[2] != 2 || snapshot.Batches[10] != 1 {
		t.Fatalf("snapshot batches = %#v", snapshot.Batches)
	}
}

func TestRecorderFlagsObservedBatchAboveProtocolMaximum(t *testing.T) {
	recorder := NewRecorder()
	recorder.Observe(11)

	if !recorder.Snapshot().AboveProtocolMaximum {
		t.Fatal("AboveProtocolMaximum = false, want true")
	}
}
