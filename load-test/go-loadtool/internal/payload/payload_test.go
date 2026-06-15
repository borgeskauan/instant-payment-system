package payload

import (
	"encoding/json"
	"testing"
)

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

func TestExtractPacs008EndToEndID(t *testing.T) {
	body := Pacs008("tx-1", "10000001", "20000001", 12345)
	got, kind, err := ExtractNotification(body)
	if err != nil {
		t.Fatal(err)
	}
	if got != "tx-1" || kind != KindPacs008 {
		t.Fatalf("got id=%s kind=%s", got, kind)
	}
}

func TestExtractPacs002OriginalEndToEndID(t *testing.T) {
	body := Pacs002("tx-1")
	got, kind, err := ExtractNotification(body)
	if err != nil {
		t.Fatal(err)
	}
	if got != "tx-1" || kind != KindPacs002 {
		t.Fatalf("got id=%s kind=%s", got, kind)
	}
}
