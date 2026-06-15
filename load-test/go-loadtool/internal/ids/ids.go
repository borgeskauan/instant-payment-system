package ids

import "fmt"

type Pair struct {
	Payer    string
	Receiver string
}

func PSPPair(number int) Pair {
	suffix := fmt.Sprintf("%06d", number)
	return Pair{
		Payer:    "10" + suffix,
		Receiver: "20" + suffix,
	}
}

func TransactionID(runID string, seq uint64) string {
	return fmt.Sprintf("%s-%d", runID, seq)
}
