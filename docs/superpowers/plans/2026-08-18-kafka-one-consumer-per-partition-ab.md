# Kafka one-consumer-per-partition A/B implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Measure whether assigning one child consumer to each Kafka partition removes the ingress regression of the keyed single-balance SPI without changing the workload or resources.

**Architecture:** Keep the existing ISPB record key, eight-partition topics and shared SPI listener factory. Configure the Compose SPI service with listener concurrency `8`, giving each independent PACS.008 and PACS.002 consumer group up to one child consumer per partition, then compare one qualified diagnostic run with the immutable keyed/concurrency-3 control.

**Tech Stack:** Spring Boot 3.5, Spring Kafka, Docker Compose, Bash load-test runner, Go load-tool, PostgreSQL diagnostics, Kafka CLI.

## Global Constraints

- Work only in `/tmp/instant-payment-system-reservation-balance-ab` on branch `reservation-balance-ab`; do not merge or push.
- Preserve `mixed-outcomes-2k-diagnostic` exactly, including 80/20 hot-pair traffic, ten hot pairs and replay settings.
- Preserve eight partitions, the ISPB key, assignment strategy, batches, poll settings, acknowledgements, SQL and transaction boundaries.
- Keep the SPI at one vCPU and retain all default diagnostics.
- Use `reservation-balance-kafka-key-diagnostic/20260817_234042` as the immutable keyed/concurrency-3 control.
- Recreate containers and volumes once without deleting Docker build caches; qualify the new stack with the existing smoke policy.
- Execute exactly one concurrency-8 diagnostic run and no 15-minute run. Do not rerun the diagnostic to improve its result.

---

### Task 1: Configure one SPI child consumer per partition

**Files:**
- Create: `load-test/tests/spi-kafka-concurrency-config-test.sh`
- Modify: `infra/docker-compose.yml`

**Interfaces:**
- Consumes: Spring Boot relaxed binding for environment variable `SPI_KAFKA_LISTENER_CONCURRENCY` to property `spi.kafka.listener-concurrency`.
- Produces: the rendered `spi` service environment contains `SPI_KAFKA_LISTENER_CONCURRENCY: "8"`; both listeners continue using `spiKafkaListenerContainerFactory`.

- [x] **Step 1: Write the failing rendered-configuration test**

Create the executable test:

```bash
#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
compose_config="$(docker compose -f "$ROOT_DIR/infra/docker-compose.yml" config)"

if ! grep -Fq 'SPI_KAFKA_LISTENER_CONCURRENCY: "8"' <<< "$compose_config"; then
    echo "rendered SPI service does not use one consumer per Kafka partition" >&2
    exit 1
fi
```

- [x] **Step 2: Run the focused test and verify RED**

Run `bash load-test/tests/spi-kafka-concurrency-config-test.sh`.

Expected: exit `1` with `rendered SPI service does not use one consumer per Kafka partition` because the Compose service does not yet override concurrency.

- [x] **Step 3: Add the minimal runtime configuration**

Add this environment entry to the existing `spi` service in `infra/docker-compose.yml`:

```yaml
      SPI_KAFKA_LISTENER_CONCURRENCY: 8
```

Do not change `spi/src/main/resources/application.yml`; this commit is an isolated performance-stack experiment, not yet a new application-wide default.

- [x] **Step 4: Run focused GREEN checks**

```bash
bash load-test/tests/spi-kafka-concurrency-config-test.sh
bash -n load-test/tests/spi-kafka-concurrency-config-test.sh
docker compose -f infra/docker-compose.yml config
```

Expected: all commands exit `0`.

- [x] **Step 5: Run the pre-traffic regression suites**

```bash
cd spi
./mvnw test
cd ../kafka-producer
./mvnw test
cd ../load-test/go-loadtool
go test ./...
cd ..
for test_script in tests/*-test.sh; do bash "$test_script"; done
cd ..
git diff --check
```

Expected: SPI and Kafka producer suites have zero failures/errors, all Go packages and shell tests pass, and `git diff --check` is silent.

- [x] **Step 6: Commit the isolated configuration**

```bash
git add infra/docker-compose.yml load-test/tests/spi-kafka-concurrency-config-test.sh
git commit -m "perf: use one SPI consumer per Kafka partition"
```

---

### Task 2: Qualify and execute the single concurrency-8 diagnostic

**Files:**
- Modify: `docs/board/Atividades/agora/estabilizar-teste-carga-budget-cpu.md`
- Modify: `docs/board/Atividades/Backlog/produto-dominio/substituir-buckets-por-reserva-no-saldo.md`
- Modify: `docs/superpowers/plans/2026-08-18-kafka-one-consumer-per-partition-ab.md`

**Interfaces:**
- Consumes: committed Compose override from Task 1, existing `prepare-performance-environment.sh`, existing `run-load-test.sh`, control bundle `load-test/results/reservation-balance-kafka-key-diagnostic/20260817_234042`.
- Produces: one qualified concurrency-8 result bundle, a KEEP/DISCARD/INCONCLUSIVE decision, and documented phase-aligned evidence.

- [x] **Step 1: Recreate and qualify the performance stack once**

From `load-test`, run exactly once:

```bash
GOFLAGS=-buildvcs=false GOCACHE=/tmp/kafka-concurrency-eight-go-cache ./prepare-performance-environment.sh
```

The preparer may execute its built-in maximum of three functional smoke attempts, but it must perform one initial `docker compose down -v --remove-orphans` and preserve Docker build caches. Stop if readiness or smoke correctness fails.

- [x] **Step 2: Prove the running SPI received concurrency 8**

```bash
docker inspect spi --format '{{range .Config.Env}}{{println .}}{{end}}'
docker exec kafka kafka-consumer-groups --bootstrap-server kafka:9092 --all-groups --describe
```

Expected: the SPI environment includes `SPI_KAFKA_LISTENER_CONCURRENCY=8`; after smoke quiescence each eight-partition SPI group has eight active consumer IDs with one assigned partition each.

- [x] **Step 3: Execute exactly one measured diagnostic**

From `load-test`, run once:

```bash
GOFLAGS=-buildvcs=false GOCACHE=/tmp/kafka-concurrency-eight-go-cache ./run-load-test.sh --profile mixed-outcomes-2k-diagnostic reservation-balance-kafka-concurrency-eight-diagnostic
```

Accept runner exit `0` or `1`: `1` means the measured report gates rejected the run, not necessarily that the bundle is unusable. Any other status is an operational failure. Do not start another measured run.

- [x] **Step 4: Preserve immediate lag/distribution and perform one later quiescence check**

Immediately after the runner exits, run once:

```bash
docker exec kafka kafka-consumer-groups --bootstrap-server kafka:9092 --all-groups --describe
```

After a fixed 30-second wait, run the sole later check:

```bash
sleep 30
./scripts/check-kafka-quiescence.sh
```

Do not use the later check to rerun the workload. It distinguishes backlog from loss only within the existing Kafka-lag heuristic.

- [x] **Step 5: Validate bundle completeness and compare authoritative windows**

Resolve the single directory below `load-test/results/reservation-balance-kafka-concurrency-eight-diagnostic/` and reject ambiguity. Verify that no expected input, event, report or diagnostics file is empty. Use `run-window.json` to filter both control and candidate timestamps to `[active_started_at, generation_ended_at)`.

Record:

```text
PACS.008 active started / 2xx / timeout / rolling minimum and maximum
PACS.002 active and total started / accepted / timeout
happy-path and insufficient-funds matched / missing / contradictory
PACS.008 and PACS.002 replay violations
immediate and later payment / status / gateway lag
PostgreSQL active CPU average / maximum
participant-balance lock calls / rows / total / mean / maximum
native participant-balance waits above one second
partition offsets and per-consumer ownership
```

Compare against the keyed/concurrency-3 control, not the older unkeyed run. Apply the decision rules from the approved design without rerunning ambiguous or unfavorable evidence.

- [x] **Step 6: Document the result and run final verification**

Add the exact bundle, active window, table of comparable metrics, lock evidence, partition ownership, smoke history and decision to the active stabilization task. Add a concise architectural consequence to the single-balance backlog task. Mark every completed plan checkbox.

Run fresh final checks:

```bash
cd spi
./mvnw test
cd ../kafka-producer
./mvnw test
cd ../load-test/go-loadtool
go test ./...
cd ..
for test_script in tests/*-test.sh; do bash "$test_script"; done
cd ..
docker compose -f infra/docker-compose.yml config
git diff --check
```

- [x] **Step 7: Commit only the experimental branch documentation**

```bash
git add docs/board/Atividades/agora/estabilizar-teste-carga-budget-cpu.md \
  docs/board/Atividades/Backlog/produto-dominio/substituir-buckets-por-reserva-no-saldo.md \
  docs/superpowers/plans/2026-08-18-kafka-one-consumer-per-partition-ab.md
git commit -m "docs: record one-consumer-per-partition experiment"
```

Confirm both the experimental worktree and the original `estabilizing-performance` worktree are clean. Leave the experimental branch and result bundle in place; do not merge or push.
