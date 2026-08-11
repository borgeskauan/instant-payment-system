package events

import (
	"bufio"
	"encoding/csv"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"slices"
	"strconv"
)

type Start struct {
	EndToEndID            string
	PayerISPB             string
	ReceiverISPB          string
	CreatedAtNS           int64
	RequestStartedAtNS    int64
	RequestDoneAtNS       int64
	HTTPStatus            int
	ScenarioName          string
	Pacs008ReplaySelected bool
}

type Replay struct {
	EndToEndID         string
	PayerISPB          string
	ScenarioName       string
	MessageType        string
	RequestStartedAtNS int64
	RequestDoneAtNS    int64
	HTTPStatus         int
}

type Notification struct {
	EndToEndID   string
	ISPB         string
	EventType    string
	ReceivedAtNS int64
	StatusCode   string
	ReasonCodes  []string
}

const (
	EventPacs008Received = "pacs008_received"
	EventPacs002Received = "pacs002_received"
	EventPacs002Sent     = "pacs002_sent"
	MessagePacs008       = "pacs.008"
)

var legacyStartHeader = []string{"end_to_end_id", "payer_ispb", "receiver_ispb", "created_at_ns", "request_started_at_ns", "request_done_at_ns", "http_status", "scenario_name"}
var startHeader = []string{"end_to_end_id", "payer_ispb", "receiver_ispb", "created_at_ns", "request_started_at_ns", "request_done_at_ns", "http_status", "scenario_name", "pacs008_replay_selected"}
var notificationHeader = []string{"end_to_end_id", "ispb", "event_type", "received_at_ns", "status_code", "reason_codes"}
var replayHeader = []string{"end_to_end_id", "payer_ispb", "scenario_name", "message_type", "request_started_at_ns", "request_done_at_ns", "http_status"}

type StartWriter struct {
	file   *os.File
	buffer *bufio.Writer
	csv    *csv.Writer
}

func NewStartWriter(path string) (*StartWriter, error) {
	file, err := os.Create(path)
	if err != nil {
		return nil, err
	}
	buffer := bufio.NewWriterSize(file, 4*1024*1024)
	writer := csv.NewWriter(buffer)
	if err := writer.Write(startHeader); err != nil {
		_ = file.Close()
		return nil, err
	}
	return &StartWriter{file: file, buffer: buffer, csv: writer}, nil
}

func (w *StartWriter) Write(row Start) error {
	return w.csv.Write([]string{
		row.EndToEndID,
		row.PayerISPB,
		row.ReceiverISPB,
		strconv.FormatInt(row.CreatedAtNS, 10),
		strconv.FormatInt(row.RequestStartedAtNS, 10),
		strconv.FormatInt(row.RequestDoneAtNS, 10),
		strconv.Itoa(row.HTTPStatus),
		row.ScenarioName,
		strconv.FormatBool(row.Pacs008ReplaySelected),
	})
}

func (w *StartWriter) Close() error {
	w.csv.Flush()
	if err := w.csv.Error(); err != nil {
		_ = w.file.Close()
		return err
	}
	if err := w.buffer.Flush(); err != nil {
		_ = w.file.Close()
		return err
	}
	return w.file.Close()
}

type NotificationWriter struct {
	file   *os.File
	buffer *bufio.Writer
	csv    *csv.Writer
}

type ReplayWriter struct {
	file   *os.File
	buffer *bufio.Writer
	csv    *csv.Writer
}

func NewReplayWriter(path string) (*ReplayWriter, error) {
	file, err := os.Create(path)
	if err != nil {
		return nil, err
	}
	buffer := bufio.NewWriterSize(file, 4*1024*1024)
	writer := csv.NewWriter(buffer)
	if err := writer.Write(replayHeader); err != nil {
		_ = file.Close()
		return nil, err
	}
	return &ReplayWriter{file: file, buffer: buffer, csv: writer}, nil
}

func (w *ReplayWriter) Write(row Replay) error {
	return w.csv.Write([]string{
		row.EndToEndID,
		row.PayerISPB,
		row.ScenarioName,
		row.MessageType,
		strconv.FormatInt(row.RequestStartedAtNS, 10),
		strconv.FormatInt(row.RequestDoneAtNS, 10),
		strconv.Itoa(row.HTTPStatus),
	})
}

func (w *ReplayWriter) Close() error {
	w.csv.Flush()
	if err := w.csv.Error(); err != nil {
		_ = w.file.Close()
		return err
	}
	if err := w.buffer.Flush(); err != nil {
		_ = w.file.Close()
		return err
	}
	return w.file.Close()
}

func NewNotificationWriter(path string) (*NotificationWriter, error) {
	file, err := os.Create(path)
	if err != nil {
		return nil, err
	}
	buffer := bufio.NewWriterSize(file, 4*1024*1024)
	writer := csv.NewWriter(buffer)
	if err := writer.Write(notificationHeader); err != nil {
		_ = file.Close()
		return nil, err
	}
	return &NotificationWriter{file: file, buffer: buffer, csv: writer}, nil
}

func (w *NotificationWriter) Write(row Notification) error {
	reasonCodes := row.ReasonCodes
	if reasonCodes == nil {
		reasonCodes = []string{}
	}
	encodedReasonCodes, err := json.Marshal(reasonCodes)
	if err != nil {
		return err
	}
	return w.csv.Write([]string{
		row.EndToEndID,
		row.ISPB,
		row.EventType,
		strconv.FormatInt(row.ReceivedAtNS, 10),
		row.StatusCode,
		string(encodedReasonCodes),
	})
}

func (w *NotificationWriter) Close() error {
	w.csv.Flush()
	if err := w.csv.Error(); err != nil {
		_ = w.file.Close()
		return err
	}
	if err := w.buffer.Flush(); err != nil {
		_ = w.file.Close()
		return err
	}
	return w.file.Close()
}

func ReadStarts(path string) ([]Start, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	reader := csv.NewReader(file)
	header, err := reader.Read()
	if err != nil {
		return nil, err
	}
	legacy := slices.Equal(header, legacyStartHeader)
	if !legacy && !slices.Equal(header, startHeader) {
		return nil, fmt.Errorf("starts header is %v, want %v", header, startHeader)
	}

	var rows []Start
	for {
		record, err := reader.Read()
		if err == io.EOF {
			return rows, nil
		}
		if err != nil {
			return nil, err
		}
		row, err := parseStart(record, legacy)
		if err != nil {
			return nil, err
		}
		rows = append(rows, row)
	}
}

func ReadReplays(path string) ([]Replay, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	reader := csv.NewReader(file)
	header, err := reader.Read()
	if err != nil {
		return nil, err
	}
	if !slices.Equal(header, replayHeader) {
		return nil, fmt.Errorf("replays header is %v, want %v", header, replayHeader)
	}

	var rows []Replay
	for {
		record, err := reader.Read()
		if err == io.EOF {
			return rows, nil
		}
		if err != nil {
			return nil, err
		}
		row, err := parseReplay(record)
		if err != nil {
			return nil, err
		}
		rows = append(rows, row)
	}
}

func ReadNotifications(path string) ([]Notification, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	reader := csv.NewReader(file)
	header, err := reader.Read()
	if err != nil {
		return nil, err
	}
	if !slices.Equal(header, notificationHeader) {
		return nil, fmt.Errorf("notifications header is %v, want %v", header, notificationHeader)
	}

	var rows []Notification
	for {
		record, err := reader.Read()
		if err == io.EOF {
			return rows, nil
		}
		if err != nil {
			return nil, err
		}
		row, err := parseNotification(record)
		if err != nil {
			return nil, err
		}
		rows = append(rows, row)
	}
}

func parseStart(record []string, legacy bool) (Start, error) {
	wantColumns := len(startHeader)
	if legacy {
		wantColumns = len(legacyStartHeader)
	}
	if len(record) != wantColumns {
		return Start{}, fmt.Errorf("start record has %d columns, want %d", len(record), wantColumns)
	}
	createdAtNS, err := strconv.ParseInt(record[3], 10, 64)
	if err != nil {
		return Start{}, err
	}
	requestStartedAtNS, err := strconv.ParseInt(record[4], 10, 64)
	if err != nil {
		return Start{}, err
	}
	requestDoneAtNS, err := strconv.ParseInt(record[5], 10, 64)
	if err != nil {
		return Start{}, err
	}
	status, err := strconv.Atoi(record[6])
	if err != nil {
		return Start{}, err
	}
	replaySelected := false
	if !legacy {
		replaySelected, err = strconv.ParseBool(record[8])
		if err != nil {
			return Start{}, err
		}
	}
	return Start{
		EndToEndID:            record[0],
		PayerISPB:             record[1],
		ReceiverISPB:          record[2],
		CreatedAtNS:           createdAtNS,
		RequestStartedAtNS:    requestStartedAtNS,
		RequestDoneAtNS:       requestDoneAtNS,
		HTTPStatus:            status,
		ScenarioName:          record[7],
		Pacs008ReplaySelected: replaySelected,
	}, nil
}

func parseReplay(record []string) (Replay, error) {
	if len(record) != len(replayHeader) {
		return Replay{}, fmt.Errorf("replay record has %d columns, want %d", len(record), len(replayHeader))
	}
	requestStartedAtNS, err := strconv.ParseInt(record[4], 10, 64)
	if err != nil {
		return Replay{}, err
	}
	requestDoneAtNS, err := strconv.ParseInt(record[5], 10, 64)
	if err != nil {
		return Replay{}, err
	}
	status, err := strconv.Atoi(record[6])
	if err != nil {
		return Replay{}, err
	}
	return Replay{
		EndToEndID:         record[0],
		PayerISPB:          record[1],
		ScenarioName:       record[2],
		MessageType:        record[3],
		RequestStartedAtNS: requestStartedAtNS,
		RequestDoneAtNS:    requestDoneAtNS,
		HTTPStatus:         status,
	}, nil
}

func parseNotification(record []string) (Notification, error) {
	if len(record) != len(notificationHeader) {
		return Notification{}, fmt.Errorf("notification record has %d columns, want %d", len(record), len(notificationHeader))
	}
	receivedAtNS, err := strconv.ParseInt(record[3], 10, 64)
	if err != nil {
		return Notification{}, err
	}
	var reasonCodes []string
	if err := json.Unmarshal([]byte(record[5]), &reasonCodes); err != nil {
		return Notification{}, fmt.Errorf("parse notification reason codes: %w", err)
	}
	if reasonCodes == nil {
		return Notification{}, fmt.Errorf("notification reason codes must be a JSON array")
	}
	return Notification{
		EndToEndID:   record[0],
		ISPB:         record[1],
		EventType:    record[2],
		ReceivedAtNS: receivedAtNS,
		StatusCode:   record[4],
		ReasonCodes:  reasonCodes,
	}, nil
}
