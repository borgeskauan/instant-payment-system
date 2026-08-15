# Native TLS Handshake JFR Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable the JDK-native `jdk.TLSHandshake` event only in the `kafka-producer` load-test JFR recording and use it to decide whether connection churn merits the next A/B experiment.

**Architecture:** Extend the existing internal JFR helper to forward optional event settings to `JFR.start`. The runner supplies one ephemeral override for `kafka-producer`; SPI and notification-gateway keep the unchanged `profile` recording. Existing JFR and load-test artifacts remain authoritative.

**Tech Stack:** Bash, `jcmd`/JFR from JDK 21, Docker Compose, existing load-test shell tests.

## Global Constraints

- Do not edit or copy `profile.jfc` on the host or in a container.
- Do not change Java application code, workload profiles, SLA reports, resource limits, connection settings, timeouts, Kafka settings, or database behavior.
- Do not introduce a custom `KafkaPublish` event in this slice.
- Keep `diagnostics/jfr/kafka-producer.jfr` as the only artifact carrying the new evidence.
- A JFR start failure must abort before load generation.
- Keep all changes uncommitted for review through `git diff`.

---

### Task 1: Forward the native TLS event setting only to the ingress recording

**Files:**
- Modify: `load-test/tests/jfr-diagnostics-test.sh`
- Modify: `load-test/scripts/container-jfr.sh`
- Modify: `load-test/run-load-test.sh:562-592`

**Interfaces:**
- Consumes: `container-jfr.sh start <container> <recording-name> <container-jfr-file> [event-setting ...]`.
- Produces: `JFR.start settings=profile ... jdk.TLSHandshake#enabled=true` for `kafka-producer` only.

- [x] **Step 1: Add failing helper and runner assertions**

In `jfr-diagnostics-test.sh`, run the real helper through a fake `docker` and assert its final invocation is:

```text
exec kafka-producer jcmd 1 JFR.start name=test settings=profile filename=/tmp/test.jfr dumponexit=true jdk.TLSHandshake#enabled=true
```

Then change the existing runner expectation so only this line gains the setting:

```text
start kafka-producer kafka-producer-load-test /tmp/kafka-producer-load-test.jfr jdk.TLSHandshake#enabled=true
```

Keep the SPI and notification-gateway lines unchanged.

- [x] **Step 2: Run the focused test and verify the red state**

Run:

```bash
bash load-test/tests/jfr-diagnostics-test.sh
```

Expected: FAIL because `container-jfr.sh start` rejects the optional argument and the runner omits it.

- [x] **Step 3: Implement optional event-setting forwarding**

Change the helper start signature to accept optional trailing arguments:

```bash
start_jfr() {
    local container="$1"
    local recording_name="$2"
    local container_file="$3"
    shift 3
    local -a event_settings=("$@")

    # existing stop/remove calls
    run_jcmd "$container" JFR.start \
        name="$recording_name" \
        settings=profile \
        filename="$container_file" \
        dumponexit=true \
        "${event_settings[@]}"
}
```

Accept `start` when it has at least four CLI arguments and call:

```bash
start_jfr "$2" "$3" "$4" "${@:5}"
```

Update usage to document `[event-setting ...]`.

- [x] **Step 4: Forward settings through the runner**

Allow `start_container_jfr` to receive optional arguments after its log file and pass them to the helper. Start the producer recording with:

```bash
start_container_jfr \
    "$KAFKA_PRODUCER_CONTAINER" \
    "kafka-producer-load-test" \
    "/tmp/kafka-producer-load-test.jfr" \
    "${target_dir}/logs/jfr/kafka-producer.log" \
    'jdk.TLSHandshake#enabled=true'
```

Do not add an override to the other two recordings.

- [x] **Step 5: Run focused tests and syntax checks**

Run:

```bash
bash load-test/tests/jfr-diagnostics-test.sh
bash -n load-test/scripts/container-jfr.sh load-test/run-load-test.sh load-test/tests/jfr-diagnostics-test.sh
```

Expected: PASS.

---

### Task 2: Verify the native event and repeat the short diagnostic

**Files:**
- Runtime output: `load-test/results/<run-tag>/<timestamp>/`
- Modify after analysis: `docs/board/Atividades/agora/estabilizar-teste-carga-budget-cpu.md`

**Interfaces:**
- Consumes: existing `--jfr`, `mixed-outcomes-smoke`, and `mixed-outcomes-2k-diagnostic`.
- Produces: native `jdk.TLSHandshake` events inside `diagnostics/jfr/kafka-producer.jfr` and an evidence-backed next decision.

- [x] **Step 1: Run complete automated verification**

Run:

```bash
cd load-test/go-loadtool
GOPATH=/tmp/go GOCACHE=/tmp/go-build-cache go test ./...

cd ../..
for test_script in load-test/tests/*.sh; do
    bash "$test_script"
done

bash -n load-test/run-load-test.sh load-test/scripts/*.sh load-test/tests/*.sh
git diff --check
```

Expected: all commands exit zero.

- [x] **Step 2: Start the existing stack and run the JFR smoke**

Run from the repository root:

```bash
docker compose -f infra/docker-compose.yml up -d --build
cd load-test
./run-load-test.sh --profile mixed-outcomes-smoke --jfr tls-handshake-smoke
```

The report may be SLA-invalid, but business outcomes and replay invariants must remain correct.

- [x] **Step 3: Gate the diagnostic on native-event compatibility**

Locate the smoke result and run:

```bash
jfr summary diagnostics/jfr/kafka-producer.jfr | grep 'jdk.TLSHandshake'
jfr print --events jdk.TLSHandshake diagnostics/jfr/kafka-producer.jfr
```

Expected: the summary count is greater than zero. If it is zero, stop; record the JDK/Netty compatibility result and do not run the 2k diagnostic.

- [x] **Step 4: Run the short 2k diagnostic**

Run:

```bash
./run-load-test.sh \
  --profile mixed-outcomes-2k-diagnostic \
  --jfr \
  --spi-trace \
  --postgres-statements \
  tls-handshake-diagnostic
```

Preserve the invalid report and all artifacts if the known bottleneck reproduces.

- [x] **Step 5: Compare handshakes with the observed workload**

Use the JFR event timestamps and durations together with all HTTP-attempt CSVs, `diagnostics/container-stats.csv`, and timeouts. Record whether handshakes:

```text
continue throughout active load and track CPU/timeouts
or
remain concentrated in setup/warmup while CPU/timeouts continue
```

Do not introduce a fixed handshake/request threshold.

- [x] **Step 6: Update the active task and stop the stack**

Record the exact run directory, event count/timing, request count, CPU/timeouts, interpretation, and next single hypothesis in the stabilization task. Then run:

```bash
cd ..
docker compose -f infra/docker-compose.yml down
```

Preserve volumes, diagnostic results, and build cache.
