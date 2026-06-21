package events

import (
	"bufio"
	"encoding/csv"
	"fmt"
	"io"
	"os"
	"strconv"
)

type Start struct {
	EndToEndID      string
	PayerISPB       string
	ReceiverISPB    string
	CreatedAtNS     int64
	RequestDoneAtNS int64
	HTTPStatus      int
}

type Notification struct {
	EndToEndID   string
	ISPB         string
	EventType    string
	ReceivedAtNS int64
}

const (
	EventPacs008Received = "pacs008_received"
	EventPacs002Received = "pacs002_received"
	EventPacs002Sent     = "pacs002_sent"
)

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
	if err := writer.Write([]string{"end_to_end_id", "payer_ispb", "receiver_ispb", "created_at_ns", "request_done_at_ns", "http_status"}); err != nil {
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
		strconv.FormatInt(row.RequestDoneAtNS, 10),
		strconv.Itoa(row.HTTPStatus),
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

func NewNotificationWriter(path string) (*NotificationWriter, error) {
	file, err := os.Create(path)
	if err != nil {
		return nil, err
	}
	buffer := bufio.NewWriterSize(file, 4*1024*1024)
	writer := csv.NewWriter(buffer)
	if err := writer.Write([]string{"end_to_end_id", "ispb", "event_type", "received_at_ns"}); err != nil {
		_ = file.Close()
		return nil, err
	}
	return &NotificationWriter{file: file, buffer: buffer, csv: writer}, nil
}

func (w *NotificationWriter) Write(row Notification) error {
	return w.csv.Write([]string{
		row.EndToEndID,
		row.ISPB,
		row.EventType,
		strconv.FormatInt(row.ReceivedAtNS, 10),
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
	if _, err := reader.Read(); err != nil {
		return nil, err
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
		row, err := parseStart(record)
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
	if _, err := reader.Read(); err != nil {
		return nil, err
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

func parseStart(record []string) (Start, error) {
	if len(record) != 6 {
		return Start{}, fmt.Errorf("start record has %d columns, want 6", len(record))
	}
	createdAtNS, err := strconv.ParseInt(record[3], 10, 64)
	if err != nil {
		return Start{}, err
	}
	requestDoneAtNS, err := strconv.ParseInt(record[4], 10, 64)
	if err != nil {
		return Start{}, err
	}
	status, err := strconv.Atoi(record[5])
	if err != nil {
		return Start{}, err
	}
	return Start{
		EndToEndID:      record[0],
		PayerISPB:       record[1],
		ReceiverISPB:    record[2],
		CreatedAtNS:     createdAtNS,
		RequestDoneAtNS: requestDoneAtNS,
		HTTPStatus:      status,
	}, nil
}

func parseNotification(record []string) (Notification, error) {
	if len(record) != 4 {
		return Notification{}, fmt.Errorf("notification record has %d columns, want 4", len(record))
	}
	receivedAtNS, err := strconv.ParseInt(record[3], 10, 64)
	if err != nil {
		return Notification{}, err
	}
	return Notification{
		EndToEndID:   record[0],
		ISPB:         record[1],
		EventType:    record[2],
		ReceivedAtNS: receivedAtNS,
	}, nil
}
