package events

import (
	"os"
	"path/filepath"
	"testing"
)

func TestStartEventsRoundTrip(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "starts.csv")

	writer, err := NewStartWriter(path)
	if err != nil {
		t.Fatal(err)
	}
	err = writer.Write(Start{
		EndToEndID:         "tx-1",
		PayerISPB:          "10000001",
		ReceiverISPB:       "20000001",
		CreatedAtNS:        10,
		RequestStartedAtNS: 15,
		RequestDoneAtNS:    20,
		HTTPStatus:         200,
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}

	rows, err := ReadStarts(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || rows[0].EndToEndID != "tx-1" {
		t.Fatalf("rows = %#v", rows)
	}
	if rows[0].HTTPStatus != 200 {
		t.Fatalf("HTTPStatus = %d, want 200", rows[0].HTTPStatus)
	}
	if rows[0].CreatedAtNS != 10 {
		t.Fatalf("CreatedAtNS = %d, want 10", rows[0].CreatedAtNS)
	}
	if rows[0].RequestStartedAtNS != 15 {
		t.Fatalf("RequestStartedAtNS = %d, want 15", rows[0].RequestStartedAtNS)
	}
	if rows[0].RequestDoneAtNS != 20 {
		t.Fatalf("RequestDoneAtNS = %d, want 20", rows[0].RequestDoneAtNS)
	}
}

func TestReadStartsAcceptsLegacyStartEvents(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "starts.csv")
	data := "end_to_end_id,payer_ispb,receiver_ispb,created_at_ns,request_done_at_ns,http_status\n" +
		"tx-1,10000001,20000001,10,20,200\n"
	if err := os.WriteFile(path, []byte(data), 0o644); err != nil {
		t.Fatal(err)
	}

	rows, err := ReadStarts(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 {
		t.Fatalf("len(rows) = %d, want 1", len(rows))
	}
	if rows[0].RequestStartedAtNS != rows[0].CreatedAtNS {
		t.Fatalf("RequestStartedAtNS = %d, want CreatedAtNS %d", rows[0].RequestStartedAtNS, rows[0].CreatedAtNS)
	}
}

func TestNotificationEventsRoundTrip(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "events.csv")

	writer, err := NewNotificationWriter(path)
	if err != nil {
		t.Fatal(err)
	}
	err = writer.Write(Notification{
		EndToEndID:   "tx-1",
		ISPB:         "10000001",
		EventType:    EventPacs002Received,
		ReceivedAtNS: 30,
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}

	rows, err := ReadNotifications(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || rows[0].EventType != EventPacs002Received {
		t.Fatalf("rows = %#v", rows)
	}
}
