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
		EndToEndID:            "tx-1",
		PayerISPB:             "10000001",
		ReceiverISPB:          "20000001",
		CreatedAtNS:           10,
		RequestStartedAtNS:    15,
		RequestDoneAtNS:       20,
		HTTPStatus:            200,
		ScenarioName:          "happy-path",
		Pacs008ReplaySelected: true,
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
	if rows[0].ScenarioName != "happy-path" {
		t.Fatalf("ScenarioName = %q, want happy-path", rows[0].ScenarioName)
	}
	if !rows[0].Pacs008ReplaySelected {
		t.Fatal("Pacs008ReplaySelected = false, want true")
	}
}

func TestReadStartsAcceptsLegacyHeaderWithoutReplaySelection(t *testing.T) {
	path := filepath.Join(t.TempDir(), "starts.csv")
	data := "end_to_end_id,payer_ispb,receiver_ispb,created_at_ns,request_started_at_ns,request_done_at_ns,http_status,scenario_name\n" +
		"tx-1,10000001,20000001,10,15,20,200,happy-path\n"
	if err := os.WriteFile(path, []byte(data), 0o644); err != nil {
		t.Fatal(err)
	}
	rows, err := ReadStarts(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || rows[0].Pacs008ReplaySelected {
		t.Fatalf("legacy rows = %#v", rows)
	}
}

func TestReadStartsRejectsPreviousContracts(t *testing.T) {
	for _, header := range []string{
		"end_to_end_id,payer_ispb,receiver_ispb,created_at_ns,request_started_at_ns,request_done_at_ns,http_status,scenario_type",
		"end_to_end_id,payer_ispb,receiver_ispb,created_at_ns,request_started_at_ns,request_done_at_ns,http_status",
	} {
		path := filepath.Join(t.TempDir(), "starts.csv")
		data := header + "\n" + "tx-1,10000001,20000001,10,15,20,200,happy-path\n"
		if err := os.WriteFile(path, []byte(data), 0o644); err != nil {
			t.Fatal(err)
		}
		if _, err := ReadStarts(path); err == nil {
			t.Fatalf("ReadStarts accepted previous header %q", header)
		}
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
		StatusCode:   "RJCT",
		ReasonCodes:  []string{"AM04", "AB03"},
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
	if len(rows) != 1 || rows[0].EventType != EventPacs002Received || rows[0].StatusCode != "RJCT" || len(rows[0].ReasonCodes) != 2 || rows[0].ReasonCodes[0] != "AM04" || rows[0].ReasonCodes[1] != "AB03" {
		t.Fatalf("rows = %#v", rows)
	}
}

func TestReplayEventsRoundTrip(t *testing.T) {
	path := filepath.Join(t.TempDir(), "replays.csv")
	writer, err := NewReplayWriter(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := writer.Write(Replay{
		EndToEndID:         "tx-1",
		PayerISPB:          "10000001",
		ScenarioName:       "happy-path",
		MessageType:        MessagePacs008,
		RequestStartedAtNS: 25,
		RequestDoneAtNS:    30,
		HTTPStatus:         202,
	}); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}

	rows, err := ReadReplays(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || rows[0].EndToEndID != "tx-1" || rows[0].PayerISPB != "10000001" || rows[0].ScenarioName != "happy-path" || rows[0].MessageType != MessagePacs008 || rows[0].RequestStartedAtNS != 25 || rows[0].RequestDoneAtNS != 30 || rows[0].HTTPStatus != 202 {
		t.Fatalf("replay rows = %#v", rows)
	}
}

func TestReadReplaysRejectsUnexpectedHeader(t *testing.T) {
	path := filepath.Join(t.TempDir(), "replays.csv")
	data := "end_to_end_id,payer_ispb,http_status\n" +
		"tx-1,10000001,200\n"
	if err := os.WriteFile(path, []byte(data), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := ReadReplays(path); err == nil {
		t.Fatal("ReadReplays accepted an unexpected header")
	}
}

func TestReadNotificationsRejectsPreviousHeader(t *testing.T) {
	path := filepath.Join(t.TempDir(), "events.csv")
	data := "end_to_end_id,ispb,event_type,received_at_ns\n" +
		"tx-1,10000001,pacs002_received,30\n"
	if err := os.WriteFile(path, []byte(data), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := ReadNotifications(path); err == nil {
		t.Fatal("ReadNotifications accepted the previous four-column header")
	}
}

func TestNotificationEventsUseEmptyOutcomeForNonPacs002(t *testing.T) {
	path := filepath.Join(t.TempDir(), "events.csv")
	writer, err := NewNotificationWriter(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := writer.Write(Notification{EndToEndID: "tx-1", ISPB: "20000001", EventType: EventPacs008Received, ReceivedAtNS: 10}); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	rows, err := ReadNotifications(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || rows[0].StatusCode != "" || len(rows[0].ReasonCodes) != 0 {
		t.Fatalf("rows = %#v", rows)
	}
}
