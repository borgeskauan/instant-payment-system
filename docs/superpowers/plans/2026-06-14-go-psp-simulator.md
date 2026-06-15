# Go PSP Simulator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Go load tool that can generate high-throughput Pix/SPI traffic, simulate PSP receiver confirmations, record PSP-observed final confirmations, and calculate post-test SLA evidence up to the moment the payer PSP receives `pacs.002`.

**Architecture:** Add a focused Go module under `load-test/go-loadtool`. The `simulate` command opens persistent notification streams for payer and receiver ISPBs, sends `pacs.008` at a configured target rate, responds with `pacs.002` when receiver streams get `pacs.008`, and writes buffered evidence files. The `report` command runs after the test and computes SLA from recorded starts and final payer confirmations.

**Tech Stack:** Go, standard `net/http`, generated gRPC client from `load-test/notification.proto`, buffered CSV output, existing `load-test/load-profile.json`, existing Pix JSON templates.

---

## File Structure

- Create `load-test/go-loadtool/go.mod`
  - Own Go module for the load tool, isolated from Java services.
- Create `load-test/go-loadtool/proto/notification.proto`
  - Copy of the current load-test notification proto for Go code generation.
- Create `load-test/go-loadtool/internal/config/config.go`
  - Loads profile and command flags.
- Create `load-test/go-loadtool/internal/payload/payload.go`
  - Builds `pacs.008` and `pacs.002` JSON payloads without reflection-heavy generic mutation.
- Create `load-test/go-loadtool/internal/ids/ids.go`
  - Deterministic ISPB and transaction id generation.
- Create `load-test/go-loadtool/internal/events/events.go`
  - Buffered CSV writers/readers for start and notification evidence.
- Create `load-test/go-loadtool/internal/sim/simulator.go`
  - Runtime simulator: streams, HTTP clients, pacing, workers, graceful drain.
- Create `load-test/go-loadtool/internal/report/report.go`
  - Post-process evidence files and compute SLA.
- Create `load-test/go-loadtool/cmd/go-loadtool/main.go`
  - CLI entrypoint with `simulate` and `report`.
- Create `load-test/run-go-sla-test.sh`
  - Optional runner after the Go tool works manually; follows existing summary folder convention.

---

## Task 1: Scaffold Go Module And Proto

**Files:**
- Create: `load-test/go-loadtool/go.mod`
- Create: `load-test/go-loadtool/proto/notification.proto`
- Create: `load-test/go-loadtool/cmd/go-loadtool/main.go`

- [ ] **Step 1: Create the module file**

```go
module instant-payment-system/load-test/go-loadtool

go 1.22

require (
	google.golang.org/grpc v1.64.0
	google.golang.org/protobuf v1.34.1
)
```

- [ ] **Step 2: Copy the notification proto**

Copy `load-test/notification.proto` to `load-test/go-loadtool/proto/notification.proto` and add:

```proto
option go_package = "instant-payment-system/load-test/go-loadtool/internal/gen/notificationpb";
```

Keep the service and messages unchanged:

```proto
service NotificationGateway {
  rpc StreamNotifications(StreamRequest) returns (stream Notification);
}

message StreamRequest {
  string ispb = 1;
}

message Notification {
  string ispb = 1;
  string payload = 2;
}
```

- [ ] **Step 3: Add an initial CLI entrypoint**

```go
package main

import (
	"fmt"
	"os"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: go-loadtool <simulate|report>")
		os.Exit(2)
	}

	switch os.Args[1] {
	case "simulate":
		fmt.Println("simulate command is not implemented yet")
	case "report":
		fmt.Println("report command is not implemented yet")
	default:
		fmt.Fprintf(os.Stderr, "unknown command: %s\n", os.Args[1])
		os.Exit(2)
	}
}
```

- [ ] **Step 4: Verify build**

Run:

```bash
cd load-test/go-loadtool
go test ./...
go run ./cmd/go-loadtool simulate
```

Expected:

```txt
simulate command is not implemented yet
```

---

## Task 2: Add Config And Deterministic PSP Mapping

**Files:**
- Create: `load-test/go-loadtool/internal/config/config.go`
- Create: `load-test/go-loadtool/internal/ids/ids.go`
- Test: `load-test/go-loadtool/internal/config/config_test.go`
- Test: `load-test/go-loadtool/internal/ids/ids_test.go`

- [ ] **Step 1: Write config tests**

```go
package config

import "testing"

func TestDefaultConfigMatchesCurrentLoadProfile(t *testing.T) {
	cfg := Default()

	if cfg.TargetTxRate != 750 {
		t.Fatalf("TargetTxRate = %d, want 750", cfg.TargetTxRate)
	}
	if cfg.HotPspCount != 10 {
		t.Fatalf("HotPspCount = %d, want 10", cfg.HotPspCount)
	}
	if cfg.ColdPspCount != 40 {
		t.Fatalf("ColdPspCount = %d, want 40", cfg.ColdPspCount)
	}
	if cfg.HotTrafficShare != 0.80 {
		t.Fatalf("HotTrafficShare = %v, want 0.80", cfg.HotTrafficShare)
	}
}
```

- [ ] **Step 2: Implement config**

```go
package config

type Config struct {
	BaseURL         string
	GatewayAddress string
	TargetTxRate   int
	DurationSeconds int
	DrainSeconds   int
	HotPspCount    int
	ColdPspCount   int
	HotTrafficShare float64
	OutputDir       string
	SLAThresholdMs  int64
}

func Default() Config {
	return Config{
		BaseURL:          "http://localhost:8001",
		GatewayAddress:  "localhost:9090",
		TargetTxRate:    750,
		DurationSeconds: 60,
		DrainSeconds:    30,
		HotPspCount:     10,
		ColdPspCount:    40,
		HotTrafficShare: 0.80,
		OutputDir:       "summary/go-loadtool/manual",
		SLAThresholdMs:  4600,
	}
}
```

- [ ] **Step 3: Write id tests**

```go
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
```

- [ ] **Step 4: Implement ids**

```go
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
```

- [ ] **Step 5: Verify**

Run:

```bash
cd load-test/go-loadtool
go test ./internal/config ./internal/ids
```

Expected: all tests pass.

---

## Task 3: Build Payload Generation

**Files:**
- Create: `load-test/go-loadtool/internal/payload/payload.go`
- Test: `load-test/go-loadtool/internal/payload/payload_test.go`

- [ ] **Step 1: Write payload tests**

```go
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
```

- [ ] **Step 2: Implement payload builders**

Use `fmt.Appendf` or `strings.Builder` to produce compact JSON. Keep the schema equivalent to the current templates, with dynamic fields:

```go
package payload

import (
	"fmt"
	"time"
)

func Pacs008(endToEndID, payerISPB, receiverISPB string, amountCents int64) []byte {
	now := time.Now().UTC().Format(time.RFC3339Nano)
	amount := float64(amountCents) / 100
	return []byte(fmt.Sprintf(`{"GrpHdr":{"MsgId":"MSG-%s","CreDtTm":%q,"NbOfTxs":1},"CdtTrfTxInf":[{"PmtId":{"EndToEndId":%q},"IntrBkSttlmAmt":{"value":%.2f,"Ccy":"BRL"},"DbtrAgt":{"FinInstnId":{"ClrSysMmbId":{"MmbId":%q}}},"CdtrAgt":{"FinInstnId":{"ClrSysMmbId":{"MmbId":%q}}}}]}`,
		endToEndID,
		now,
		endToEndID,
		amount,
		payerISPB,
		receiverISPB,
	))
}

func Pacs002(originalEndToEndID string) []byte {
	now := time.Now().UTC().Format(time.RFC3339Nano)
	return []byte(fmt.Sprintf(`{"GrpHdr":{"MsgId":"STATUS-%s","CreDtTm":%q,"NbOfTxs":1},"TxInfAndSts":[{"OrgnlEndToEndId":%q,"TxSts":"ACSP"}]}`,
		originalEndToEndID,
		now,
		originalEndToEndID,
	))
}
```

- [ ] **Step 3: Verify**

Run:

```bash
cd load-test/go-loadtool
go test ./internal/payload
```

Expected: all tests pass.

---

## Task 4: Evidence File Writers And Readers

**Files:**
- Create: `load-test/go-loadtool/internal/events/events.go`
- Test: `load-test/go-loadtool/internal/events/events_test.go`

- [ ] **Step 1: Write event round-trip tests**

```go
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
		EndToEndID: "tx-1",
		PayerISPB: "10000001",
		ReceiverISPB: "20000001",
		CreatedAtNS: 10,
		RequestDoneAtNS: 20,
		HTTPStatus: 200,
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
}
```

- [ ] **Step 2: Implement event types**

```go
package events

type Start struct {
	EndToEndID      string
	PayerISPB       string
	ReceiverISPB    string
	CreatedAtNS     int64
	RequestDoneAtNS int64
	HTTPStatus      int
}

type Notification struct {
	EndToEndID  string
	ISPB        string
	EventType   string
	ReceivedAtNS int64
}

const (
	EventPacs008Received = "pacs008_received"
	EventPacs002Received = "pacs002_received"
	EventPacs002Sent     = "pacs002_sent"
)
```

- [ ] **Step 3: Implement buffered CSV**

Implement:

```go
func NewStartWriter(path string) (*StartWriter, error)
func (w *StartWriter) Write(row Start) error
func (w *StartWriter) Close() error
func ReadStarts(path string) ([]Start, error)

func NewNotificationWriter(path string) (*NotificationWriter, error)
func (w *NotificationWriter) Write(row Notification) error
func (w *NotificationWriter) Close() error
func ReadNotifications(path string) ([]Notification, error)
```

Use `bufio.NewWriterSize(file, 4*1024*1024)` and `encoding/csv`.

- [ ] **Step 4: Verify**

Run:

```bash
cd load-test/go-loadtool
go test ./internal/events
```

Expected: all tests pass.

---

## Task 5: Implement Notification Parsing

**Files:**
- Create: `load-test/go-loadtool/internal/payload/extract.go`
- Test: `load-test/go-loadtool/internal/payload/extract_test.go`

- [ ] **Step 1: Write extraction tests**

```go
package payload

import "testing"

func TestExtractPacs008EndToEndID(t *testing.T) {
	body := Pacs008("tx-1", "10000001", "20000001", 12345)
	got, kind, err := ExtractNotification(body)
	if err != nil {
		t.Fatal(err)
	}
	if got != "tx-1" || kind != "pacs008" {
		t.Fatalf("got id=%s kind=%s", got, kind)
	}
}

func TestExtractPacs002OriginalEndToEndID(t *testing.T) {
	body := Pacs002("tx-1")
	got, kind, err := ExtractNotification(body)
	if err != nil {
		t.Fatal(err)
	}
	if got != "tx-1" || kind != "pacs002" {
		t.Fatalf("got id=%s kind=%s", got, kind)
	}
}
```

- [ ] **Step 2: Implement extraction**

Use a small struct-based JSON decode:

```go
package payload

import (
	"encoding/json"
	"errors"
)

type notificationEnvelope struct {
	CdtTrfTxInf []struct {
		PmtId struct {
			EndToEndId string
		}
	}
	TxInfAndSts []struct {
		OrgnlEndToEndId string
	}
}

func ExtractNotification(body []byte) (endToEndID string, kind string, err error) {
	var env notificationEnvelope
	if err := json.Unmarshal(body, &env); err != nil {
		return "", "", err
	}
	if len(env.CdtTrfTxInf) > 0 && env.CdtTrfTxInf[0].PmtId.EndToEndId != "" {
		return env.CdtTrfTxInf[0].PmtId.EndToEndId, "pacs008", nil
	}
	if len(env.TxInfAndSts) > 0 && env.TxInfAndSts[0].OrgnlEndToEndId != "" {
		return env.TxInfAndSts[0].OrgnlEndToEndId, "pacs002", nil
	}
	return "", "", errors.New("notification payload does not contain a known transaction id")
}
```

- [ ] **Step 3: Verify**

Run:

```bash
cd load-test/go-loadtool
go test ./internal/payload
```

Expected: all tests pass.

---

## Task 6: Implement Post-Test Reporter

**Files:**
- Create: `load-test/go-loadtool/internal/report/report.go`
- Test: `load-test/go-loadtool/internal/report/report_test.go`

- [ ] **Step 1: Write report test**

```go
package report

import (
	"testing"

	"instant-payment-system/load-test/go-loadtool/internal/events"
)

func TestSummaryCountsSLA(t *testing.T) {
	starts := []events.Start{
		{EndToEndID: "tx-1", CreatedAtNS: 0, HTTPStatus: 200},
		{EndToEndID: "tx-2", CreatedAtNS: 0, HTTPStatus: 200},
		{EndToEndID: "tx-3", CreatedAtNS: 0, HTTPStatus: 500},
	}
	notifications := []events.Notification{
		{EndToEndID: "tx-1", EventType: events.EventPacs002Received, ReceivedAtNS: 1_000_000_000},
		{EndToEndID: "tx-2", EventType: events.EventPacs002Received, ReceivedAtNS: 5_000_000_000},
	}

	summary := Build(starts, notifications, 4600)

	if summary.Started != 3 {
		t.Fatalf("Started = %d, want 3", summary.Started)
	}
	if summary.Accepted != 2 {
		t.Fatalf("Accepted = %d, want 2", summary.Accepted)
	}
	if summary.Confirmed != 2 {
		t.Fatalf("Confirmed = %d, want 2", summary.Confirmed)
	}
	if summary.MissedSLA != 1 {
		t.Fatalf("MissedSLA = %d, want 1", summary.MissedSLA)
	}
	if summary.NeverConfirmed != 0 {
		t.Fatalf("NeverConfirmed = %d, want 0", summary.NeverConfirmed)
	}
}
```

- [ ] **Step 2: Implement report summary**

Implement:

```go
type Summary struct {
	Started        int
	Accepted       int
	Confirmed      int
	NeverConfirmed int
	MissedSLA      int
	P50Ms          float64
	P95Ms          float64
	P99Ms          float64
	MaxMs          float64
}

func Build(starts []events.Start, notifications []events.Notification, slaThresholdMs int64) Summary
```

Rules:
- `Started` counts all starts.
- `Accepted` counts starts with `HTTPStatus >= 200 && HTTPStatus < 300`.
- `Confirmed` requires a `pacs002_received` event for the same `endToEndId`.
- `NeverConfirmed` counts accepted starts with no final `pacs002_received`.
- Duration is `pacs002_received.received_at_ns - start.created_at_ns`.
- `MissedSLA` counts confirmed durations greater than `slaThresholdMs`.
- Percentiles are calculated over confirmed durations only.

- [ ] **Step 3: Verify**

Run:

```bash
cd load-test/go-loadtool
go test ./internal/report
```

Expected: all tests pass.

---

## Task 7: Implement Simulator Runtime

**Files:**
- Create: `load-test/go-loadtool/internal/sim/simulator.go`
- Modify: `load-test/go-loadtool/cmd/go-loadtool/main.go`

- [ ] **Step 1: Generate gRPC Go code**

Install generators if unavailable:

```bash
go install google.golang.org/protobuf/cmd/protoc-gen-go@v1.34.1
go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@v1.4.0
```

Generate:

```bash
cd load-test/go-loadtool
protoc --go_out=. --go-grpc_out=. proto/notification.proto
```

Expected generated package:

```txt
internal/gen/notificationpb
```

- [ ] **Step 2: Implement stream handling**

For each PSP pair:
- Open stream for payer ISPB.
- Open stream for receiver ISPB.
- Receiver stream behavior:
  - On `pacs.008`, write `pacs008_received`.
  - Immediately POST `pacs.002` to `/{receiverISPB}/transfer/status`.
  - Write `pacs002_sent` after HTTP response.
- Payer stream behavior:
  - On `pacs.002`, write `pacs002_received`.

- [ ] **Step 3: Implement generator pacing**

Use a single ticker loop with integer carry:

```go
interval := time.Second / time.Duration(cfg.TargetTxRate)
next := time.Now()
for seq := uint64(0); time.Since(start) < duration; seq++ {
	now := time.Now()
	if sleep := next.Sub(now); sleep > 0 {
		time.Sleep(sleep)
	}
	next = next.Add(interval)
	// dispatch pacs.008 send to a bounded worker pool
}
```

Use a bounded channel so the simulator exposes its own backpressure instead of unbounded memory growth.

- [ ] **Step 4: Implement HTTP client reuse**

Use:

```go
transport := &http.Transport{
	MaxIdleConns:        4096,
	MaxIdleConnsPerHost: 4096,
	MaxConnsPerHost:     4096,
	IdleConnTimeout:     90 * time.Second,
}
client := &http.Client{
	Transport: transport,
	Timeout:   5 * time.Second,
}
```

- [ ] **Step 5: Implement CLI flags**

Support:

```txt
simulate
  --rate 2000
  --duration 60s
  --drain 30s
  --base-url http://localhost:8001
  --gateway localhost:9090
  --hot-psps 10
  --cold-psps 40
  --hot-share 0.80
  --out load-test/summary/<tag>/<timestamp>/go-loadtool
```

- [ ] **Step 6: Manual smoke test**

Run services through compose, then:

```bash
cd load-test/go-loadtool
go run ./cmd/go-loadtool simulate --rate 10 --duration 10s --drain 10s --out /tmp/go-loadtool-smoke
go run ./cmd/go-loadtool report --starts /tmp/go-loadtool-smoke/starts.csv --events /tmp/go-loadtool-smoke/events.csv --sla-ms 4600
```

Expected:
- `starts.csv` exists.
- `events.csv` exists.
- Report shows accepted and confirmed transactions.

---

## Task 8: Add Runner Integration

**Files:**
- Create: `load-test/run-go-sla-test.sh`
- Modify: `load-test/analyze-summary.py` only if useful after first manual reports exist.

- [ ] **Step 1: Create runner**

The runner should:
- Create `load-test/summary/<tag>/<timestamp>/`.
- Run `provision-funds.sh` by default.
- Start existing Kafka lag, system stats, and process stats samplers if available.
- Run `go-loadtool simulate`.
- Run `go-loadtool report`.
- Save:
  - `starts.csv`
  - `events.csv`
  - `sla-report.json`
  - `sla-report.txt`
  - `system-stats.csv`
  - `process-stats.csv`
  - `kafka-lag.csv`

- [ ] **Step 2: Add usage**

Support:

```txt
./run-go-sla-test.sh <tag>
./run-go-sla-test.sh --rate 2000 --duration 60s --drain 30s <tag>
```

- [ ] **Step 3: Verify low-rate run**

Run:

```bash
cd load-test
./run-go-sla-test.sh --rate 50 --duration 15s --drain 10s go-smoke
```

Expected:
- Summary folder created.
- Report exists.
- Kafka lag and system/process stats exist.

---

## Task 9: Performance Ramp Plan

**Files:**
- No new files required.

- [ ] **Step 1: Run smoke**

```bash
cd load-test
./run-go-sla-test.sh --rate 50 --duration 15s --drain 10s go-smoke
```

Pass criteria:
- confirmed > 0
- never_confirmed = 0
- missed_sla = 0

- [ ] **Step 2: Run 500/s**

```bash
cd load-test
./run-go-sla-test.sh --rate 500 --duration 60s --drain 30s go-500
```

Pass criteria:
- started approximately 30000
- accepted equals started or failures are explicitly explained
- confirmed equals accepted
- missed_sla = 0
- p99 < 4600ms

- [ ] **Step 3: Run 1000/s**

```bash
cd load-test
./run-go-sla-test.sh --rate 1000 --duration 60s --drain 45s go-1000
```

Pass criteria:
- started approximately 60000
- confirmed equals accepted
- missed_sla = 0
- p99 < 4600ms
- Kafka lag drains after load stops

- [ ] **Step 4: Run 2000/s**

```bash
cd load-test
./run-go-sla-test.sh --rate 2000 --duration 60s --drain 60s go-2000
```

Pass criteria:
- started approximately 120000
- confirmed equals accepted
- never_confirmed = 0
- missed_sla = 0
- p99 < 4600ms
- max < 4600ms if the requirement is literally all transactions under SLA
- Kafka lag drains after load stops

---

## Open Technical Decisions

- The simulator should start with `net/http`. Move to `fasthttp` only if Go becomes the bottleneck.
- The first version should use CSV. Move to binary only if file writing becomes visible in CPU profiles.
- The first version should run one process. Add `--shard-index` and `--shard-count` only if a single Go process cannot generate enough pressure.
- The first version should keep report calculation completely post-test. Do not add real-time SLA maps in the hot path.

---

## Self-Review

- This plan measures the real SLA endpoint: payer PSP receiving `pacs.002` through `notification-gateway`.
- The PSP receiver confirmation role is implemented by the simulator stream handler, not by SPI.
- The hot path writes only buffered evidence and performs required PSP behavior.
- The report is post-processing, so percentile calculation does not steal CPU during the test.
- The ramp plan avoids jumping directly to 2k/s without validating correctness at lower rates.
