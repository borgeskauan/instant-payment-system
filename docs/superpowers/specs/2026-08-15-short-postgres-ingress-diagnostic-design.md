# Short PostgreSQL ingress diagnostic

## Purpose

Reproduce the initial PACS.008 persistence bottleneck under the real 2,000 TPS
workload without running the 15-minute benchmark. The experiment must classify
the dominant delay as PostgreSQL CPU, I/O, lock contention, or connection
pressure before any tuning or architectural change is selected.

This is a diagnostic run, not a performance approval run. Its report may be
invalid and instrumentation overhead is acceptable as long as the collected
evidence is complete and the original bottleneck is reproduced.

## Workload

Add `mixed-outcomes-2k-diagnostic` as a profile with:

- 2,000 original payments per second;
- 15 seconds of warmup;
- 60 seconds of active load;
- 30 seconds of drain;
- the same scenarios, participant distribution, funding, amounts, expectations,
  5% PACS.008 replay, and 5% PACS.002 replay as
  `mixed-outcomes-2k-15m`.

Only the execution window differs from the official 15-minute profile. The
diagnostic profile does not replace that profile and cannot satisfy the final
stabilization criterion.

## Evidence collection

Keep the existing `--postgres-statements` option and extend the diagnostic data
collected when it is enabled. Do not add another runner flag.

### Statement snapshot

Continue producing `diagnostics/postgres-statements.csv`, adding the available
statement-level I/O timing fields, including shared and temporary block read and
write times. Existing block, WAL, call, row, and execution-time fields remain.

### Runtime PostgreSQL samples

Produce `diagnostics/postgres-activity.csv`. Sample every 250 milliseconds from
immediately before load generation until the load-tool finishes. Each observed
backend row records at least:

- sampling timestamp;
- PID and application name;
- connection state;
- query ID;
- query and transaction ages;
- `wait_event_type` and `wait_event`;
- blocking PIDs returned by `pg_blocking_pids()`.

The raw per-backend representation is authoritative. CPU, I/O, lock, and
connection summaries can be derived after the run without adding analysis to
the measured hot path.

### PostgreSQL I/O snapshots

Produce `diagnostics/postgres-io.csv` with before and after snapshots from
`pg_stat_io` and the relevant `pg_stat_database` counters. Label each row with
its snapshot phase instead of resetting global server statistics.

### Container resources

Produce `diagnostics/container-stats.csv` at one-second resolution for
PostgreSQL, Kafka, kafka-producer, SPI, and notification-gateway. Record the
sample timestamp, CPU, memory, network I/O, and block I/O reported by Docker.

The runner starts and stops both samplers, collects their files on successful or
failed load-tool execution, and leaves no sampler process behind. A sampler
failure makes an otherwise successful run fail. If the load-tool also fails,
the runner preserves the load-tool exit code and reports the sampler failure in
its diagnostic log instead of replacing the original error.

Existing JFR and SPI trace collection remain enabled explicitly for the
experiment. SPI trace continues to be sampled and is used only for stage
latency correlation.

## Classification

Use the evidence together rather than treating one metric as conclusive:

- **CPU pressure:** PostgreSQL remains close to its CPU limit while the ingress
  query is active, active backends usually have no wait event, and measured I/O
  and lock waits are not dominant.
- **I/O pressure:** PostgreSQL activity repeatedly reports I/O waits and the
  statement/database I/O time and block deltas grow materially with ingress
  latency.
- **Lock contention:** active sessions repeatedly report lock waits and
  `pg_blocking_pids()` identifies blocking sessions during the same interval.
- **Connection pressure:** SPI work waits for database connections or the pool
  remains saturated while PostgreSQL itself is not executing enough concurrent
  work. JFR/thread evidence and PostgreSQL connection states must agree.
- **Mixed limit:** if more than one signal is material, report their temporal
  order and relative contribution rather than forcing a single label.

Correlate those signals with the sampled SPI latency from `request_consumed` to
`request_saved`, Kafka lag growth, HTTP transport failures, and generation gaps.

## Execution and acceptance

First verify the new collection path with `mixed-outcomes-smoke`. Then recreate
the stack from a clean test environment and run:

```bash
cd load-test
./run-load-test.sh \
  --profile mixed-outcomes-2k-diagnostic \
  --jfr \
  --spi-trace \
  --postgres-statements \
  postgres-ingress-diagnostic
```

The experiment is successful when:

- it reproduces material growth in PACS.008 ingress latency or consumer lag;
- all diagnostic files are present and parseable;
- runtime samples cover warmup, active load, and drain;
- the evidence distinguishes CPU, I/O, locks, connections, or explicitly shows
  a mixed/ambiguous result;
- no tuning, resource change, query rewrite, concurrency change, or bucket
  refactor is included in the same experiment.

If the runtime evidence localizes the delay to the ingress query but cannot
explain its internal cost, the next experiment may use sampled `auto_explain`
or a representative offline `EXPLAIN (ANALYZE, BUFFERS, WAL)`. Those mechanisms
are deliberately outside this first run because they are more intrusive.

## Verification

- Profile tests protect the 2,000 TPS rate, short durations, workload mix, and
  replay configuration.
- Shell tests cover sampler start/stop, headers, successful collection, cleanup,
  and preservation of the load-tool exit code when a sampler fails.
- `bash -n` validates changed scripts.
- Existing Go and load-test shell suites remain green.
- A diagnostic smoke proves the final runner path before the 2,000 TPS run.
