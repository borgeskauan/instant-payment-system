# Short PostgreSQL Ingress Diagnostic Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add and execute a short 2,000 TPS diagnostic workload that distinguishes PostgreSQL CPU, I/O, lock, and connection pressure in the initial PACS.008 persistence path.

**Architecture:** Reuse the current profile-driven runner and its `--postgres-statements` option. Add one focused PostgreSQL runtime sampler and one container-resource sampler, orchestrated by the runner and written into the existing diagnostic bundle; correlate their timestamped evidence after the run with the existing SPI trace, JFR, Kafka lag, and report.

**Tech Stack:** Bash, PostgreSQL 17 statistics views, Docker CLI, Go profile loader/tests, JFR, existing load-test runner.

## Global Constraints

- This run is diagnostic and may produce `valid: false`; it does not approve performance.
- Keep 2,000 original payments/s, the existing mixed-outcomes scenarios, participant distribution, funding, expectations, and 5% replay for both PACS.008 and PACS.002.
- Use 15 seconds warmup, 60 seconds active load, and 30 seconds drain.
- Do not change queries, indexes, buckets, resource limits, pools, Kafka settings, or application concurrency in this work.
- Keep the existing `--postgres-statements` CLI option; do not add another diagnostic flag.
- Runtime PostgreSQL activity is sampled every 250 ms; container resources are sampled every second.
- Diagnostic collection must run outside the load-tool hot path.
- A diagnostic failure fails an otherwise successful run; a load-tool failure retains precedence over a simultaneous diagnostic failure.
- Do not delete Docker build cache. Stop the stack without `-v` after preserving the experiment evidence.
- Keep all changes uncommitted so they remain reviewable through `git diff`.

---

### Task 1: Add the short 2k diagnostic profile

**Files:**
- Create: `load-test/profiles/mixed-outcomes-2k-diagnostic.json`
- Modify: `load-test/go-loadtool/internal/config/config_test.go:430-455`

**Interfaces:**
- Consumes: existing schema-v1 profile contract and `mixed-outcomes-2k-15m` workload definition.
- Produces: profile name `mixed-outcomes-2k-diagnostic`, load rate `2000`, warmup `15s`, duration `1m`, drain `30s`.

- [ ] **Step 1: Write the failing profile contract test**

Add a test beside `TestMixedOutcomesLongProfileDefinesStabilizationWorkload`:

```go
func TestMixedOutcomesDiagnosticProfileDefinesShortInvestigationWorkload(t *testing.T) {
	profilesDir := filepath.Join("..", "..", "..", "profiles")
	diagnostic, err := loadProfileFromDir(profilesDir, "mixed-outcomes-2k-diagnostic")
	if err != nil {
		t.Fatal(err)
	}
	long, err := loadProfileFromDir(profilesDir, "mixed-outcomes-2k-15m")
	if err != nil {
		t.Fatal(err)
	}

	if diagnostic.Load.TargetTxRate != 2000 ||
		diagnostic.Load.Warmup != 15*time.Second ||
		diagnostic.Load.Duration != time.Minute ||
		diagnostic.Load.Drain != 30*time.Second {
		t.Fatalf("mixed-outcomes-2k-diagnostic Load = %#v", diagnostic.Load)
	}
	if !reflect.DeepEqual(diagnostic.Replay, long.Replay) ||
		!reflect.DeepEqual(diagnostic.Scenarios, long.Scenarios) ||
		!reflect.DeepEqual(diagnostic.Connections, long.Connections) ||
		!reflect.DeepEqual(diagnostic.Reporting, long.Reporting) {
		t.Fatal("diagnostic workload differs from mixed-outcomes-2k-15m outside the execution window")
	}
}
```

- [ ] **Step 2: Run the test and verify that the missing profile fails**

Run:

```bash
cd load-test/go-loadtool
go test ./internal/config -run TestMixedOutcomesDiagnosticProfileDefinesShortInvestigationWorkload -count=1
```

Expected: FAIL because `mixed-outcomes-2k-diagnostic.json` does not exist.

- [ ] **Step 3: Create the diagnostic profile**

Create a semantic copy of `mixed-outcomes-2k-15m.json`, changing only:

```json
{
  "name": "mixed-outcomes-2k-diagnostic",
  "load": {
    "targetTxRate": 2000,
    "warmup": "15s",
    "duration": "1m",
    "drain": "30s"
  }
}
```

The abbreviated object above identifies the fields to change; the created file must retain all connection, replay, scenario, funding, expectation, and reporting objects from the long profile.

- [ ] **Step 4: Verify profile loading and normalized metadata**

Run:

```bash
cd load-test/go-loadtool
go test ./internal/config -run 'TestMixedOutcomes(Diagnostic|Long)Profile' -count=1
go run ./cmd/go-loadtool validate-profile --profile mixed-outcomes-2k-diagnostic
```

Expected: tests PASS; validation reports 2,000 TPS, 15/60/30-second windows, two scenarios, and 5%/10-second replays.

---

### Task 2: Capture PostgreSQL statement I/O and runtime activity

**Files:**
- Modify: `load-test/scripts/postgres-statements.sh`
- Create: `load-test/scripts/postgres-runtime.sh`
- Create: `load-test/tests/postgres-diagnostics-test.sh`

**Interfaces:**
- Consumes: container `postgres`, database/user defaults `postgres`, PostgreSQL 17 views `pg_stat_activity`, `pg_stat_io`, `pg_stat_database`, and `pg_stat_statements`.
- Produces: `postgres-runtime.sh sample-activity <output.csv>` and `postgres-runtime.sh snapshot-io <before|after> <output.csv>`.
- Produces CSV headers:
  - activity: `sampled_at_ns,pid,application_name,state,query_id,query_age_ms,transaction_age_ms,wait_event_type,wait_event,blocking_pids`
  - I/O: `phase,sampled_at_ns,source,scope,metric,value`

- [ ] **Step 1: Write the failing diagnostic script test**

Create a fake `docker` executable that logs the SQL passed to it and returns one activity row or normalized I/O rows. Exercise bounded sampling with environment overrides:

```bash
export PATH="$tmp_dir/bin:$PATH"
export POSTGRES_ACTIVITY_INTERVAL_MS=0
export POSTGRES_ACTIVITY_MAX_SAMPLES=2

"$ROOT_DIR/scripts/postgres-runtime.sh" \
    sample-activity "$tmp_dir/postgres-activity.csv"
"$ROOT_DIR/scripts/postgres-runtime.sh" \
    snapshot-io before "$tmp_dir/postgres-io.csv"
"$ROOT_DIR/scripts/postgres-runtime.sh" \
    snapshot-io after "$tmp_dir/postgres-io.csv"

[[ "$(head -n 1 "$tmp_dir/postgres-activity.csv")" == \
   "sampled_at_ns,pid,application_name,state,query_id,query_age_ms,transaction_age_ms,wait_event_type,wait_event,blocking_pids" ]]
[[ "$(wc -l < "$tmp_dir/postgres-activity.csv")" -eq 3 ]]
[[ "$(head -n 1 "$tmp_dir/postgres-io.csv")" == \
   "phase,sampled_at_ns,source,scope,metric,value" ]]
grep -q '^before,' "$tmp_dir/postgres-io.csv"
grep -q '^after,' "$tmp_dir/postgres-io.csv"
```

Also run `postgres-statements.sh snapshot` through the fake and assert its captured SQL contains:

```bash
grep -q 'shared_blk_read_time' "$tmp_dir/docker-invocations.log"
grep -q 'shared_blk_write_time' "$tmp_dir/docker-invocations.log"
grep -q 'temp_blk_read_time' "$tmp_dir/docker-invocations.log"
grep -q 'temp_blk_write_time' "$tmp_dir/docker-invocations.log"
```

- [ ] **Step 2: Run the shell test and verify that the new script/columns are absent**

Run:

```bash
bash load-test/tests/postgres-diagnostics-test.sh
```

Expected: FAIL because `postgres-runtime.sh` and the statement I/O columns do not exist.

- [ ] **Step 3: Add statement-level I/O timing columns**

Extend the `pg_stat_statements` snapshot SELECT after the block counters with:

```sql
round(shared_blk_read_time::numeric, 3) AS shared_blk_read_time_ms,
round(shared_blk_write_time::numeric, 3) AS shared_blk_write_time_ms,
round(local_blk_read_time::numeric, 3) AS local_blk_read_time_ms,
round(local_blk_write_time::numeric, 3) AS local_blk_write_time_ms,
round(temp_blk_read_time::numeric, 3) AS temp_blk_read_time_ms,
round(temp_blk_write_time::numeric, 3) AS temp_blk_write_time_ms,
```

Keep existing execution, row, block, WAL, and normalized-query columns unchanged.

- [ ] **Step 4: Implement bounded or continuous activity sampling**

Implement `sample-activity` as a loop. Defaults are `0.25` seconds and unlimited samples; `POSTGRES_ACTIVITY_MAX_SAMPLES` exists only to make the script deterministically testable. Each iteration appends the output of this shape using `\copy (...) TO STDOUT WITH CSV` so values remain correctly quoted:

```sql
SELECT
    floor(extract(epoch FROM statement_timestamp()) * 1000000000)::numeric::text,
    pid,
    application_name,
    state,
    query_id,
    CASE WHEN query_start IS NULL THEN NULL
         ELSE round(extract(epoch FROM (statement_timestamp() - query_start)) * 1000, 3)
    END,
    CASE WHEN xact_start IS NULL THEN NULL
         ELSE round(extract(epoch FROM (statement_timestamp() - xact_start)) * 1000, 3)
    END,
    wait_event_type,
    wait_event,
    array_to_string(pg_blocking_pids(pid), ';')
FROM pg_stat_activity
WHERE datname = current_database()
  AND backend_type = 'client backend'
  AND pid <> pg_backend_pid()
  AND application_name <> 'load-test-postgres-diagnostics'
ORDER BY pid
```

Set `PGAPPNAME=load-test-postgres-diagnostics` for sampler connections. Trap `INT` and `TERM` and exit zero after the current sample so normal runner shutdown is not reported as failure. A psql/query error exits nonzero.

- [ ] **Step 5: Implement normalized before/after I/O snapshots**

Implement `snapshot-io` with a `phase` argument restricted to `before|after`. Append one normalized metric per row to the same file. Use:

```sql
SELECT 'pg_stat_io' AS source,
       concat_ws('|', backend_type, object, context) AS scope,
       metric,
       value
FROM pg_stat_io
CROSS JOIN LATERAL (VALUES
    ('reads', reads::numeric),
    ('read_time_ms', read_time::numeric),
    ('writes', writes::numeric),
    ('write_time_ms', write_time::numeric),
    ('writebacks', writebacks::numeric),
    ('writeback_time_ms', writeback_time::numeric),
    ('extends', extends::numeric),
    ('extend_time_ms', extend_time::numeric),
    ('fsyncs', fsyncs::numeric),
    ('fsync_time_ms', fsync_time::numeric)
) AS metrics(metric, value)
WHERE value IS NOT NULL
UNION ALL
SELECT 'pg_stat_database', datname, metric, value
FROM pg_stat_database
CROSS JOIN LATERAL (VALUES
    ('blks_read', blks_read::numeric),
    ('blks_hit', blks_hit::numeric),
    ('blk_read_time_ms', blk_read_time::numeric),
    ('blk_write_time_ms', blk_write_time::numeric),
    ('temp_files', temp_files::numeric),
    ('temp_bytes', temp_bytes::numeric),
    ('deadlocks', deadlocks::numeric)
) AS metrics(metric, value)
WHERE datname = current_database()
```

Prefix each emitted row with the validated phase and a nanosecond timestamp. Write the header only when the output file is new or empty; do not reset shared PostgreSQL statistics.

- [ ] **Step 6: Run the focused test and syntax checks**

Run:

```bash
bash load-test/tests/postgres-diagnostics-test.sh
bash -n load-test/scripts/postgres-statements.sh
bash -n load-test/scripts/postgres-runtime.sh
```

Expected: PASS.

---

### Task 3: Capture timestamped container resource samples

**Files:**
- Create: `load-test/scripts/container-stats.sh`
- Create: `load-test/tests/container-stats-test.sh`

**Interfaces:**
- Consumes: Docker stats for `postgres`, `kafka`, `kafka-producer`, `spi`, and `notification-gateway`.
- Produces: `container-stats.sh sample <output.csv>` with header `sampled_at_ns,container,cpu_percent,memory_usage,network_io,block_io`.

- [ ] **Step 1: Write the failing bounded-sampler test**

Provide a fake streaming `docker stats` returning complete refreshes for exactly
the five configured containers, then run:

```bash
export PATH="$tmp_dir/bin:$PATH"
export CONTAINER_STATS_INTERVAL_MS=0
export CONTAINER_STATS_MAX_SAMPLES=2
"$ROOT_DIR/scripts/container-stats.sh" sample "$tmp_dir/container-stats.csv"

[[ "$(head -n 1 "$tmp_dir/container-stats.csv")" == \
   "sampled_at_ns,container,cpu_percent,memory_usage,network_io,block_io" ]]
[[ "$(wc -l < "$tmp_dir/container-stats.csv")" -eq 11 ]]
for container in postgres kafka kafka-producer spi notification-gateway; do
    [[ "$(grep -c ",$container," "$tmp_dir/container-stats.csv")" -eq 2 ]]
done
```

- [ ] **Step 2: Run the test and verify the script is missing**

Run:

```bash
bash load-test/tests/container-stats-test.sh
```

Expected: FAIL because `container-stats.sh` does not exist.

- [ ] **Step 3: Implement the sampler**

Open one continuous stream with:

```bash
docker stats \
    --format '{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}\t{{.BlockIO}}' \
    postgres kafka kafka-producer spi notification-gateway
```

Treat each complete refresh as one candidate sample, emit at most once per
configured one-second interval, and prepend one nanosecond timestamp to all five
rows. Support the bounded test variables. Trap `INT` and `TERM`, terminate and
wait for the Docker stream, and exit zero after the current sample. Treat a
missing container, partial refresh, unexpected container, or Docker failure as
a nonzero sampler failure rather than silently emitting partial data.

- [ ] **Step 4: Run focused tests and syntax checks**

Run:

```bash
bash load-test/tests/container-stats-test.sh
bash -n load-test/scripts/container-stats.sh
```

Expected: PASS.

---

### Task 4: Orchestrate the new diagnostics in the runner

**Files:**
- Modify: `load-test/run-load-test.sh`
- Modify: `load-test/tests/diagnostics-layout-test.sh`
- Modify: `load-test/tests/runner-exit-test.sh`

**Interfaces:**
- Consumes:
  - `postgres-runtime.sh sample-activity <output.csv>`
  - `postgres-runtime.sh snapshot-io <before|after> <output.csv>`
  - `container-stats.sh sample <output.csv>`
- Produces under `--postgres-statements`:
  - `diagnostics/postgres-statements.csv`
  - `diagnostics/postgres-activity.csv`
  - `diagnostics/postgres-io.csv`
  - `diagnostics/container-stats.csv`
  - matching logs under `logs/`.

- [ ] **Step 1: Extend the diagnostic layout test with long-running fakes**

Make fake runtime samplers write their headers and then wait until terminated:

```bash
case "$1" in
  sample-activity)
    printf '%s\n' 'sampled_at_ns,pid,application_name,state,query_id,query_age_ms,transaction_age_ms,wait_event_type,wait_event,blocking_pids' > "$2"
    trap 'exit 0' INT TERM
    while :; do sleep 1; done
    ;;
  snapshot-io)
    phase="$2"
    output="$3"
    if [[ ! -s "$output" ]]; then
      printf '%s\n' 'phase,sampled_at_ns,source,scope,metric,value' > "$output"
    fi
    printf '%s\n' "$phase,1,pg_stat_database,postgres,deadlocks,0" >> "$output"
    ;;
esac
```

Add a matching container fake and assert all four CSVs and their logs exist after start/collect.

- [ ] **Step 2: Extend runner exit-precedence coverage**

Add `diagnostics-failure` to the current driver. `run_loadtool` writes a valid report and returns zero; `collect_optional_diagnostics` returns `19`. Assert runner exit `19`.

Keep the existing `go-failure` assertion: load-tool exit `23` plus diagnostic exit `19` must still return `23`.

- [ ] **Step 3: Run the tests and confirm they fail**

Run:

```bash
bash load-test/tests/diagnostics-layout-test.sh
bash load-test/tests/runner-exit-test.sh
```

Expected: layout test FAILS because the new artifacts are not produced; exit test FAILS because diagnostics-only failure mode is unsupported.

- [ ] **Step 4: Add runner state and lifecycle helpers**

Add explicit PID/state variables for both samplers and focused helpers:

```text
start_postgres_runtime_diagnostics <target_dir>
stop_postgres_runtime_diagnostics <target_dir>
start_container_stats <target_dir>
stop_container_stats <target_dir>
```

Starting PostgreSQL diagnostics must perform this order:

```text
enable/reset pg_stat_statements
snapshot PostgreSQL I/O: before
start activity sampler
start container sampler
```

Collection must perform:

```text
stop activity sampler
stop container sampler
snapshot PostgreSQL I/O: after
snapshot pg_stat_statements
disable pg_stat_statements
```

Store PIDs immediately after background launch. `stop_*` sends `TERM` only to a live PID, always calls `wait`, records an unexpected nonzero status, clears PID/state, and does not let one collection failure skip the remaining artifacts.

- [ ] **Step 5: Make cleanup idempotent and preserve error precedence**

Extend `cleanup` to stop any live sampler before disabling statement stats. Cleanup calls are best-effort and cannot overwrite the run result.

Keep the existing main-flow precedence:

```bash
if ((loadtool_status != 0)); then
    return "$loadtool_status"
fi
if ((diagnostics_status != 0)); then
    return "$diagnostics_status"
fi
```

`collect_optional_diagnostics` must accumulate failures while attempting every enabled collector, then return nonzero once collection is complete.

- [ ] **Step 6: Run focused runner tests**

Run:

```bash
bash load-test/tests/diagnostics-layout-test.sh
bash load-test/tests/runner-exit-test.sh
bash -n load-test/run-load-test.sh
```

Expected: PASS; no fake sampler process remains after either test.

---

### Task 5: Document the diagnostic workload and verify automation

**Files:**
- Modify: `docs/board/Atividades/agora/estabilizar-teste-carga-budget-cpu.md`
- Modify: `README.md` only if its current load-test section enumerates diagnostic commands; otherwise leave it unchanged.

**Interfaces:**
- Consumes: profile and diagnostic runner behavior from Tasks 1–4.
- Produces: one explicit command for the short diagnostic and an evidence checklist in the active stabilization task.

- [ ] **Step 1: Update the active task without claiming a diagnosis**

Add the short profile under Fase 2 as the current diagnostic experiment. Record:

```text
mixed-outcomes-2k-diagnostic
2,000 TPS; 15 s warmup; 60 s active; 30 s drain
same mix and 5% replays as mixed-outcomes-2k-15m
purpose: classify PACS.008 ingress delay as CPU, I/O, lock, connection, or mixed
```

Reference the diagnostic artifact names. Keep the bucket replacement conditional and do not mark the bottleneck classification complete before the run is analyzed.

- [ ] **Step 2: Run the complete automated suite**

Run:

```bash
cd load-test/go-loadtool
go test ./...

cd ../../..
for test_script in load-test/tests/*.sh; do
    bash "$test_script"
done

bash -n load-test/run-load-test.sh load-test/scripts/*.sh
git diff --check
```

Expected: every command exits zero.

- [ ] **Step 3: Inspect the diff for accidental tuning**

Run:

```bash
git diff -- infra/docker-compose.yml spi kafka-producer notification-gateway
git diff --stat
git status --short
```

Expected: no application, SQL, Kafka, pool, resource-limit, or bucket implementation change; only profile, diagnostics, tests, plan/spec, and task documentation appear.

---

### Task 6: Prove the collection path, execute the experiment, and classify it

**Files:**
- Runtime output only: `load-test/results/<run-tag>/<timestamp>/`
- Modify after analysis: `docs/board/Atividades/agora/estabilizar-teste-carga-budget-cpu.md`

**Interfaces:**
- Consumes: final runner path and diagnostic profile.
- Produces: a complete diagnostic bundle and an evidence-backed classification; no tuning.

- [ ] **Step 1: Start a clean stack without deleting build cache**

Run from repository root:

```bash
docker compose -f infra/docker-compose.yml down -v --remove-orphans
docker compose -f infra/docker-compose.yml up -d --build
docker compose -f infra/docker-compose.yml ps
```

Expected: Kafka and PostgreSQL healthy; all three application containers running. Do not run `docker builder prune` or any equivalent cache deletion.

- [ ] **Step 2: Run the instrumented functional smoke**

Run:

```bash
cd load-test
./run-load-test.sh \
  --profile mixed-outcomes-smoke \
  --jfr \
  --spi-trace \
  --postgres-statements \
  postgres-diagnostics-smoke
```

An SLA-invalid exit is acceptable only if the report shows zero missing or contradictory business outcomes and zero replay violations. Verify that all seven diagnostic groups exist and are nonempty: three JFRs, SPI trace, PostgreSQL statements, PostgreSQL activity, PostgreSQL I/O, and container stats. Do not start the 2k run if collection is incomplete or a sampler remains alive.

- [ ] **Step 3: Execute the short 2k diagnostic**

Run:

```bash
./run-load-test.sh \
  --profile mixed-outcomes-2k-diagnostic \
  --jfr \
  --spi-trace \
  --postgres-statements \
  postgres-ingress-diagnostic
```

Monitor `df -h /` in another terminal. The report is expected to be invalid if the known bottleneck reproduces; preserve the complete result directory.

- [ ] **Step 4: Classify waits and saturation from immutable artifacts**

For the result directory, run:

```bash
jq '.generation, .performance, .scenarios[].outcome' sla-report.json

awk -F, 'NR > 1 { key=($8 == "" ? "RUNNING" : $8 ":" $9); count[key]++ }
END { for (key in count) print key, count[key] }' \
  diagnostics/postgres-activity.csv | sort

awk -F, 'NR > 1 && $10 != "" { print $2, $5, $8, $9, $10 }' \
  diagnostics/postgres-activity.csv | head -n 50

cut -d, -f1-22 diagnostics/postgres-statements.csv | sed -n '1,15p'
jfr view --width 180 cpu-load diagnostics/jfr/spi.jfr
jfr view --width 180 gc-pauses diagnostics/jfr/spi.jfr
```

Also calculate deltas between `before` and `after` in `postgres-io.csv`, inspect the PostgreSQL and Kafka CPU series, and correlate their timestamps with SPI `request_consumed -> request_saved` latency and generation gaps.

- [ ] **Step 5: Record the classification and remaining uncertainty**

Update the active stabilization task with:

- exact result directory and code/worktree identity;
- effective resource limits;
- generation average/minimum/maximum and transport failures;
- Kafka lag and notification lag at run completion;
- ingress query calls, mean/max time, rows, block and I/O timing deltas;
- proportions of samples running, waiting on I/O, waiting on locks, and blocked;
- SPI CPU/GC and sampled consume-to-save latency;
- classification as CPU, I/O, lock, connection, mixed, or inconclusive;
- the single next hypothesis to test.

Do not record a proposed optimization as accepted. If evidence remains ambiguous, the next plan may add sampled `auto_explain` or an offline representative `EXPLAIN (ANALYZE, BUFFERS, WAL)`.

- [ ] **Step 6: Stop the stack while preserving evidence**

Run from repository root:

```bash
docker compose -f infra/docker-compose.yml down
df -h / /home
```

Expected: containers stopped, result directory retained, and build cache untouched.
