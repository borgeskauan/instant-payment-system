package events

import (
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
		EndToEndID:      "tx-1",
		PayerISPB:       "10000001",
		ReceiverISPB:    "20000001",
		CreatedAtNS:     10,
		RequestDoneAtNS: 20,
		HTTPStatus:      200,
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
