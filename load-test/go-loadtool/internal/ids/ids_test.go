package ids

import "testing"

func TestPSPPairUsesExistingISPBPattern(t *testing.T) {
	pair := PSPPair(1)
	if pair.Payer != "10000001" {
		t.Fatalf("payer = %s, want 10000001", pair.Payer)
	}
	if pair.Receiver != "20000001" {
		t.Fatalf("receiver = %s, want 20000001", pair.Receiver)
	}
}

func TestTransactionIDIsDeterministicShape(t *testing.T) {
	got := TransactionID("run-a", 42)
	want := "run-a-42"
	if got != want {
		t.Fatalf("TransactionID = %s, want %s", got, want)
	}
}
