package payload

import (
	"encoding/json"
	"testing"
)

var builtPayloadSink []byte

func TestPacs008AllocatesOnlyItsResultBuffer(t *testing.T) {
	allocations := testing.AllocsPerRun(1_000, func() {
		builtPayloadSink = Pacs008("go-1787592769302354517-123456", "10000001", "20000001", 12345)
	})

	if allocations != 1 {
		t.Fatalf("Pacs008 allocations = %.0f, want 1", allocations)
	}
}

func TestPacs002AllocatesOnlyItsResultBuffer(t *testing.T) {
	allocations := testing.AllocsPerRun(1_000, func() {
		builtPayloadSink = Pacs002("go-1787592769302354517-123456")
	})

	if allocations != 1 {
		t.Fatalf("Pacs002 allocations = %.0f, want 1", allocations)
	}
}

func TestPacs008ContainsTransactionAndISPBs(t *testing.T) {
	body := Pacs008("tx-1", "10000001", "20000001", 12345)

	var parsed map[string]any
	if err := json.Unmarshal(body, &parsed); err != nil {
		t.Fatalf("invalid json: %v", err)
	}

	tx := parsed["CdtTrfTxInf"].([]any)[0].(map[string]any)
	pmt := tx["PmtId"].(map[string]any)
	if pmt["EndToEndId"] != "tx-1" {
		t.Fatalf("EndToEndId = %v, want tx-1", pmt["EndToEndId"])
	}

	dbtrAgt := tx["DbtrAgt"].(map[string]any)
	dbtrID := dbtrAgt["FinInstnId"].(map[string]any)["ClrSysMmbId"].(map[string]any)["MmbId"]
	if dbtrID != "10000001" {
		t.Fatalf("payer ISPB = %v, want 10000001", dbtrID)
	}

	if tx["DbtrAcct"] == nil {
		t.Fatal("DbtrAcct is required")
	}
	if tx["CdtrAcct"] == nil {
		t.Fatal("CdtrAcct is required")
	}
	if tx["Dbtr"] == nil {
		t.Fatal("Dbtr is required")
	}
	if tx["Cdtr"] == nil {
		t.Fatal("Cdtr is required")
	}
}

func TestPacs002ContainsOriginalEndToEndID(t *testing.T) {
	body := Pacs002("tx-1")

	var parsed map[string]any
	if err := json.Unmarshal(body, &parsed); err != nil {
		t.Fatalf("invalid json: %v", err)
	}

	tx := parsed["TxInfAndSts"].([]any)[0].(map[string]any)
	if tx["OrgnlEndToEndId"] != "tx-1" {
		t.Fatalf("OrgnlEndToEndId = %v, want tx-1", tx["OrgnlEndToEndId"])
	}
	if tx["TxSts"] != "ACSP" {
		t.Fatalf("TxSts = %v, want ACSP", tx["TxSts"])
	}
}

func TestExtractPacs008ReturnsAllEndToEndIDs(t *testing.T) {
	body := []byte(`{
		"CdtTrfTxInf": [
			{"PmtId": {"EndToEndId": "tx-1"}},
			{"PmtId": {"EndToEndId": "tx-2"}}
		]
	}`)

	got, err := ExtractNotifications(body)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 2 {
		t.Fatalf("len = %d, want 2", len(got))
	}
	if got[0].EndToEndID != "tx-1" || got[0].Kind != KindPacs008 {
		t.Fatalf("first = %#v", got[0])
	}
	if got[1].EndToEndID != "tx-2" || got[1].Kind != KindPacs008 {
		t.Fatalf("second = %#v", got[1])
	}
}

func TestExtractPacs002OriginalEndToEndID(t *testing.T) {
	body := Pacs002("tx-1")
	got, err := ExtractNotifications(body)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 1 || got[0].EndToEndID != "tx-1" || got[0].Kind != KindPacs002 || got[0].StatusCode != "ACSP" || len(got[0].ReasonCodes) != 0 {
		t.Fatalf("got = %#v", got)
	}
}

func TestExtractPacs002ReturnsAllOutcomes(t *testing.T) {
	body := []byte(`{
		"TxInfAndSts": [
			{"OrgnlEndToEndId": "tx-1", "TxSts": "ACSC", "StsRsnInf": []},
			{"OrgnlEndToEndID": "tx-2", "TxSts": "RJCT", "StsRsnInf": [
				{"Rsn": {"Cd": "AM04"}},
				{"Rsn": {"Cd": "AB03"}}
			]}
		]
	}`)

	got, err := ExtractNotifications(body)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 2 {
		t.Fatalf("len = %d, want 2", len(got))
	}
	if got[0].EndToEndID != "tx-1" || got[0].Kind != KindPacs002 || got[0].StatusCode != "ACSC" || len(got[0].ReasonCodes) != 0 {
		t.Fatalf("first = %#v", got[0])
	}
	if got[1].EndToEndID != "tx-2" || got[1].Kind != KindPacs002 || got[1].StatusCode != "RJCT" || len(got[1].ReasonCodes) != 2 || got[1].ReasonCodes[0] != "AM04" || got[1].ReasonCodes[1] != "AB03" {
		t.Fatalf("second = %#v", got[1])
	}
}

func TestExtractPacs002PreservesMissingObservableCodes(t *testing.T) {
	got, err := ExtractNotifications([]byte(`{"TxInfAndSts":[{"OrgnlEndToEndId":"tx-1","StsRsnInf":[{"Rsn":{}}]}]}`))
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 1 || got[0].StatusCode != "" || len(got[0].ReasonCodes) != 1 || got[0].ReasonCodes[0] != "" {
		t.Fatalf("got = %#v", got)
	}
}
