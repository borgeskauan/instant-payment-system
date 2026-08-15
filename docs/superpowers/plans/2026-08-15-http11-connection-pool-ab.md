# HTTP/1.1 Connection-Pool A/B Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bound each PSP's HTTP/1.1 pool at 32 connections, record whether attempts acquire, reuse and write a connection, and run a controlled B diagnostic against the preserved unbounded baseline.

**Architecture:** Extract HTTP transport construction and attempt tracing into a focused simulator file. `httptrace` observations flow into the existing PACS.008, PACS.002 and replay event rows; report semantics stay unchanged. The runner and workload contract do not gain configuration.

**Tech Stack:** Go 1.24 `net/http` and `net/http/httptrace`, CSV event artifacts, Bash runner, Docker Compose, JDK JFR.

## Global Constraints

- Keep HTTP/1.1, mTLS, the five-second request timeout and 90-second idle timeout.
- Use a fixed internal limit of 32 connections per PSP; add no flag, environment variable or profile field.
- Change no server, workload, resource, Kafka, TLS or SLA-report configuration in the experiment.
- Extend the three existing HTTP-attempt CSVs; create no new runtime artifact.
- Keep all work uncommitted and unstaged for review through `git diff`.

---

### Task 1: Characterize the fixed HTTP/1.1 transport and trace one attempt

**Files:**
- Create: `load-test/go-loadtool/internal/sim/http_attempt_test.go`
- Create: `load-test/go-loadtool/internal/sim/http_attempt.go`
- Modify: `load-test/go-loadtool/internal/sim/simulator.go:314-360,683-701`

**Interfaces:**
- Produces: `newHTTP11Transport(*tls.Config) *http.Transport` with all pool limits set to `maxHTTP11ConnectionsPerPSP`.
- Produces: `(*simulator).post(context.Context, string, string, []byte) httpAttemptResult`.
- Produces: `httpAttemptResult{HTTPStatus, ConnectionAcquiredAtNS, RequestWrittenAtNS, ConnectionReused}`.

- [x] **Step 1: Write failing transport-limit and observation tests**

Add tests that assert `maxHTTP11ConnectionsPerPSP == 32`, all three transport
limits equal that constant, keep-alives remain enabled, and the idle timeout is
90 seconds. Use an `httptest.Server` plus one client to POST twice sequentially;
assert both attempts are written, the first is new and the second is reused.

- [x] **Step 2: Run the focused test and verify the red state**

Run:

```bash
cd load-test/go-loadtool
GOPATH=/tmp/go GOCACHE=/tmp/go-build-cache go test ./internal/sim -run 'TestHTTP11Transport|TestPostObserves' -count=1
```

Expected: FAIL because the constant, constructor and result type do not exist.

- [x] **Step 3: Implement the bounded transport and standard-library trace**

Create `http_attempt.go` with:

```go
const maxHTTP11ConnectionsPerPSP = 32

type httpAttemptResult struct {
    HTTPStatus            int
    ConnectionAcquiredAtNS int64
    RequestWrittenAtNS     int64
    ConnectionReused       bool
}
```

Use per-request atomics in `httptrace.ClientTrace.GotConn` and `WroteRequest`
so callbacks remain race-free. A request-construction, missing-client or
`client.Do` error returns status zero while preserving observations already
made. Drain and close successful response bodies exactly as today.

Move transport construction into `newHTTP11Transport`; call it from
`newHTTPClients` with the PSP-specific TLS config.

- [x] **Step 4: Run focused tests and the race detector**

Run:

```bash
GOPATH=/tmp/go GOCACHE=/tmp/go-build-cache go test -race ./internal/sim -run 'TestHTTP11Transport|TestPostObserves' -count=1
```

Expected: PASS with the second sequential request marked reused.

---

### Task 2: Persist transport observations in existing event artifacts

**Files:**
- Modify: `load-test/go-loadtool/internal/events/events.go`
- Modify: `load-test/go-loadtool/internal/events/events_test.go`
- Modify: `load-test/go-loadtool/internal/sim/simulator.go:490-625`
- Modify: simulator tests that stub or inspect `post`, if required by compilation.

**Interfaces:**
- Consumes: `httpAttemptResult` from Task 1.
- Produces on `events.Start`, `events.StatusStart` and `events.Replay`: `ConnectionAcquiredAtNS int64`, `RequestWrittenAtNS int64`, `ConnectionReused bool`.
- Appends CSV columns in that exact order: `connection_acquired_at_ns,request_written_at_ns,connection_reused`.

- [x] **Step 1: Extend round-trip tests first**

Set distinct nonzero observation values in all three event round-trip tests and
assert they survive write/read. Assert the exact new headers, preserving every
existing column before the three appended columns.

- [x] **Step 2: Run the event tests and verify the red state**

Run:

```bash
GOPATH=/tmp/go GOCACHE=/tmp/go-build-cache go test ./internal/events -count=1
```

Expected: FAIL because event structs and headers do not contain the fields.

- [x] **Step 3: Extend writers, readers and simulator rows**

Append the fields to the three structs, headers, writers and exact parsers.
Change all three POST call sites to persist the returned status and transport
observations. Leave report calculations on `RequestStartedAtNS` unchanged.

- [x] **Step 4: Run event, simulator and report tests**

Run:

```bash
GOPATH=/tmp/go GOCACHE=/tmp/go-build-cache go test ./internal/events ./internal/sim ./internal/report -count=1
```

Expected: PASS; simplified `sla-report.json` contract remains unchanged.

---

### Task 3: Verify the implementation and run B

**Files:**
- Modify after comparison: `docs/board/Atividades/agora/estabilizar-teste-carga-budget-cpu.md`
- Runtime output: `load-test/results/http11-pool-smoke/<timestamp>/`
- Runtime output: `load-test/results/http11-pool-32-diagnostic/<timestamp>/`

**Interfaces:**
- Consumes: fixed pool, extended events and existing diagnostic flags.
- Produces: an evidence-backed keep/discard decision against `tls-handshake-diagnostic/20260815_143609`.

- [x] **Step 1: Run complete automated verification**

Run all Go tests, all `load-test/tests/*.sh`, Bash syntax checks and
`git diff --check`. Expected: every command exits zero.

- [x] **Step 2: Start the stack and run a real mTLS smoke**

Run:

```bash
docker compose -f infra/docker-compose.yml up -d --build
cd load-test
./run-load-test.sh --profile mixed-outcomes-smoke --jfr http11-pool-smoke
```

Expected: external business outcomes and replay invariants remain correct. The
new CSV fields are populated, and reused connections appear.

- [x] **Step 3: Run the short B diagnostic**

Run:

```bash
./run-load-test.sh \
  --profile mixed-outcomes-2k-diagnostic \
  --jfr \
  --spi-trace \
  --postgres-statements \
  http11-pool-32-diagnostic
```

Preserve all evidence even when the SLA report is invalid.

- [x] **Step 4: Compare A and B without treating queueing as automatic failure**

For each HTTP-attempt CSV and phase, calculate attempts started, connections
acquired, requests written, reused/new connections, acquisition-wait
percentiles, 2xx and status zero. Compare JFR TLS handshakes, container CPU,
rolling throughput, outcomes and drain with A.

Accept B only when lower handshake/CPU cost accompanies improved actual write
or completion throughput without progressively growing client wait. Record an
ambiguous or negative result rather than tuning another variable.

- [x] **Step 5: Update the active task, stop the stack and reverify**

Document exact run paths, values and the keep/discard decision. Stop Compose
without `-v`, preserving volumes and build cache. Repeat full automated tests,
syntax checks, `git diff --check`, `git status --short`, and prove the index is
empty with `git diff --cached --quiet`.
