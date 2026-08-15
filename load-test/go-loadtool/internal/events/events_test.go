package events

import (
	"os"
	"path/filepath"
	"strings"
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
		EndToEndID:             "tx-1",
		PayerISPB:              "10000001",
		ReceiverISPB:           "20000001",
		CreatedAtNS:            10,
		RequestStartedAtNS:     15,
		RequestDoneAtNS:        20,
		HTTPStatus:             200,
		ScenarioName:           "happy-path",
		Pacs008ReplaySelected:  true,
		ConnectionAcquiredAtNS: 16,
		RequestWrittenAtNS:     17,
		ConnectionReused:       true,
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	assertCSVHeader(t, path, "end_to_end_id,payer_ispb,receiver_ispb,created_at_ns,request_started_at_ns,request_done_at_ns,http_status,scenario_name,pacs008_replay_selected,connection_acquired_at_ns,request_written_at_ns,connection_reused")

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
	if rows[0].ConnectionAcquiredAtNS != 16 || rows[0].RequestWrittenAtNS != 17 || !rows[0].ConnectionReused {
		t.Fatalf("transport observations = %d/%d/%t, want 16/17/true", rows[0].ConnectionAcquiredAtNS, rows[0].RequestWrittenAtNS, rows[0].ConnectionReused)
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
		EndToEndID:             "tx-1",
		SenderISPB:             "10000001",
		ScenarioName:           "happy-path",
		MessageType:            MessagePacs008,
		RequestStartedAtNS:     25,
		RequestDoneAtNS:        30,
		HTTPStatus:             202,
		ConnectionAcquiredAtNS: 26,
		RequestWrittenAtNS:     27,
		ConnectionReused:       false,
	}); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	assertCSVHeader(t, path, "end_to_end_id,sender_ispb,scenario_name,message_type,request_started_at_ns,request_done_at_ns,http_status,connection_acquired_at_ns,request_written_at_ns,connection_reused")

	rows, err := ReadReplays(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || rows[0].EndToEndID != "tx-1" || rows[0].SenderISPB != "10000001" || rows[0].ScenarioName != "happy-path" || rows[0].MessageType != MessagePacs008 || rows[0].RequestStartedAtNS != 25 || rows[0].RequestDoneAtNS != 30 || rows[0].HTTPStatus != 202 || rows[0].ConnectionAcquiredAtNS != 26 || rows[0].RequestWrittenAtNS != 27 || rows[0].ConnectionReused {
		t.Fatalf("replay rows = %#v", rows)
	}
}

func TestStatusStartEventsRoundTrip(t *testing.T) {
	path := filepath.Join(t.TempDir(), "status-starts.csv")
	writer, err := NewStatusStartWriter(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := writer.Write(StatusStart{
		EndToEndID:             "tx-1",
		SenderISPB:             "20000001",
		ScenarioName:           "insufficient-funds",
		RequestStartedAtNS:     25,
		RequestDoneAtNS:        30,
		HTTPStatus:             202,
		Pacs002ReplaySelected:  true,
		ConnectionAcquiredAtNS: 26,
		RequestWrittenAtNS:     27,
		ConnectionReused:       true,
	}); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	assertCSVHeader(t, path, "end_to_end_id,sender_ispb,scenario_name,request_started_at_ns,request_done_at_ns,http_status,pacs002_replay_selected,connection_acquired_at_ns,request_written_at_ns,connection_reused")

	rows, err := ReadStatusStarts(path)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || rows[0].EndToEndID != "tx-1" || rows[0].SenderISPB != "20000001" || rows[0].ScenarioName != "insufficient-funds" || rows[0].RequestStartedAtNS != 25 || rows[0].RequestDoneAtNS != 30 || rows[0].HTTPStatus != 202 || !rows[0].Pacs002ReplaySelected || rows[0].ConnectionAcquiredAtNS != 26 || rows[0].RequestWrittenAtNS != 27 || !rows[0].ConnectionReused {
		t.Fatalf("status start rows = %#v", rows)
	}
}

func assertCSVHeader(t *testing.T, path string, expected string) {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	actual, _, _ := strings.Cut(string(data), "\n")
	if actual != expected {
		t.Fatalf("CSV header = %q, want %q", actual, expected)
	}
}

func TestReadReplaysRejectsUnexpectedHeader(t *testing.T) {
	path := filepath.Join(t.TempDir(), "replays.csv")
	data := "end_to_end_id,unexpected,http_status\n" +
		"tx-1,10000001,200\n"
	if err := os.WriteFile(path, []byte(data), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := ReadReplays(path); err == nil {
		t.Fatal("ReadReplays accepted an unexpected header")
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
