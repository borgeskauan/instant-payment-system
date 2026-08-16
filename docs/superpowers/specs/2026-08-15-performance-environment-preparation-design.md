# Performance environment preparation

## Purpose

Reduce the wall-clock and operator time spent repeating short performance experiments. The preparation command must eliminate the invalid attempts already observed because services were not ready, JVMs and connection pools were cold, diagnostics were omitted, or work from an earlier run was still visible.

Preparation and measurement remain separate operations:

```text
prepare one code state
        |
        +-- measured run 1
        +-- measured run 2
        +-- measured run N
```

The preparation command does not start a measured profile. It leaves a qualified stack running so that `run-load-test.sh` can be invoked repeatedly. Preparation is repeated after changing the deployed code or when a new isolated baseline is required.

## Public interface

Add a top-level command:

```bash
cd load-test
./prepare-performance-environment.sh
```

The command has no profile or run-tag argument. It always qualifies the environment with `mixed-outcomes-smoke`.

Diagnostics are enabled by default for every profile executed by `run-load-test.sh`. Replace the positive diagnostic switches with switches that disable individual collectors:

```text
--no-jfr
--no-spi-trace
--no-postgres-statements
```

The preparation command accepts the same negative switches and forwards them to its smoke attempts. This makes an instrumentation-off experiment explicit while keeping the normal, fully instrumented execution terse.

There is no `fresh` or `reuse` mode. Preparation always creates an isolated environment. Docker image and build-layer caches remain intact, so an unchanged image is a cache hit.

## Preparation flow

One invocation performs these steps in order:

1. Run `docker compose -f infra/docker-compose.yml down -v --remove-orphans`.
2. Run `docker compose -f infra/docker-compose.yml up -d --build`.
3. Wait at most 120 seconds for infrastructure, applications, and Kafka consumers to become ready.
4. After readiness, wait a fixed 10 seconds before the first smoke attempt.
5. Run `mixed-outcomes-smoke`, with at most three attempts under the retry rules below.
6. After an accepted smoke, wait 10 seconds for residual asynchronous work to settle.
7. Require three consecutive zero-lag observations, one second apart, for all load-test Kafka consumer groups.
8. Exit successfully and leave the stack running.

The command prints progress to the terminal and relies on the ordinary smoke run bundles for evidence. It does not introduce an experiment manifest, orchestration log, image inventory, or additional diagnostic bundle.

The stack is also left available after a failure so it can be inspected. The next preparation invocation begins by removing that state.

## Readiness

Readiness is a bounded gate, not a fixed startup sleep. During the 120-second interval, the command polls until all of the following are true:

- PostgreSQL and Kafka report healthy;
- `kafka-producer`, `spi`, and `notification-gateway` are running;
- ports 8001, 8002, and 9090 accept connections;
- the SPI payment-request, SPI status-report, and notification-gateway consumer groups are active and stable.

Transient failures while the stack starts do not fail preparation immediately. If the complete condition is not reached before the deadline, preparation fails without starting a smoke.

The additional 10 seconds after readiness is intentional. Readiness proves that the services can participate; the short stabilization interval avoids making the first request at the instant the endpoints and consumer assignments appear.

## Smoke qualification

The current `mixed-outcomes-smoke` workload is characterized by 1,250 planned original payments. The number is a fixture of that profile and its current five-second warmup and ten-second active phase; it is not a general load-tool invariant.

An attempt qualifies the environment only when its report proves:

- all 1,250 planned original payments were started;
- every started payment received its expected HTTP 2xx response;
- every original payment initiated and received its corresponding original PACS.002;
- happy-path payments produced PACS.002 ACSC;
- insufficient-funds payments produced PACS.002 RJCT with AM04;
- there are no missing or contradictory outcomes;
- each replay type has equal started and accepted counts and zero violations;
- no load-tool, environment-preparation, or enabled-instrumentation operation failed.

The report's performance validity, rolling throughput floor, and latency SLA are deliberately ignored during this gate. The smoke qualifies functional behavior and warms the deployed path; it is not a performance result.

`run-load-test.sh` uses exit code 1 for a completed run whose `sla-report.json` is invalid and exit code 2 for an operational failure in the runner, load-tool, environment preparation, or diagnostics. Both remain nonzero for compatibility. The preparation command may inspect the functional fields after exit code 1, but aborts immediately after exit code 2. A missing, malformed, or incomplete report is an operational failure, not an acceptable performance violation.

### Retry policy

At most three smoke attempts are allowed.

An attempt may be retried when it started fewer than 1,250 originals or when some HTTP requests timed out without receiving a response. A timeout never qualifies the environment. It is retryable only when the timed-out payment still produced the expected payer PACS.002 before the deadline, all other started payments received HTTP 2xx and completed correctly, and both replay categories have no violations. The qualifier distinguishes timeout status `0` from an explicit non-2xx response through the existing `events/pacs008-starts.csv` and validates the timed-out payment's outcome through `events/notifications.csv`; this does not expand the public report.

Explicit HTTP 4xx/5xx responses, a timeout without its correct final PACS.002, missing outcomes, wrong statuses or reasons, replay violations, infrastructure failures, and instrumentation failures abort preparation immediately. This permits cold JIT/TLS/pool capacity to warm without treating a contract violation as transient.

Each invocation and attempt uses a unique smoke run tag so that qualification cannot accidentally read a stale report.

## Kafka quiescence

The existing per-run environment preparation currently observes Kafka lag once. Replace that single observation with three consecutive zero-lag readings, one second apart, for:

- SPI payment requests;
- SPI payment status reports;
- PSP notifications consumed by the notification gateway.

The same lag-checking implementation is used by the ordinary runner and by the post-smoke preparation gate. Each gate performs exactly three readings. Any nonzero or unreadable reading fails the gate; it does not wait indefinitely for the environment to become quiescent. Preparation already provides a ten-second settling interval before its post-smoke gate.

This is intentionally a heuristic. Kafka lag does not prove that no latent database or outbox work exists. Unique payment identifiers and the load-tool's correlation rules remain the safety net: if older work becomes visible in a later run, that run is invalidated rather than accepted silently.

## Repeated-test workflow

For one deployed code state:

```bash
cd load-test
./prepare-performance-environment.sh

./run-load-test.sh --profile mixed-outcomes-2k-diagnostic diagnostic-1
./run-load-test.sh --profile mixed-outcomes-2k-diagnostic diagnostic-2
./run-load-test.sh --profile mixed-outcomes-2k-diagnostic diagnostic-3
```

The preparation command is not called between those measured runs. The ordinary runner continues to validate quiescence and provision scenario balances before each run.

After changing code, preparation is run again. `docker compose up --build` may rebuild the changed image, while unchanged images and build layers come from cache. A formal A/B comparison prepares each variant independently before collecting its measured runs.

This design reduces elapsed time by preventing discarded attempts and by amortizing stack initialization and smoke qualification over several measured runs. It does not attempt to shorten the profile's actual warmup, active, replay, or drain windows.

## Exit behavior

`prepare-performance-environment.sh` returns:

- zero only when readiness, smoke qualification, settling, and stable zero lag all succeed;
- nonzero for any preparation or qualification failure.

It does not expose a performance-SLA exit status because it never initiates a measured run. `run-load-test.sh` retains its existing public behavior of returning nonzero when a completed measured report is invalid.

For `run-load-test.sh`, exit code 1 specifically means a completed but invalid report, while exit code 2 means an operational failure. This distinction lets preparation accept a functionally correct smoke whose performance fields are invalid without accidentally accepting failed instrumentation or an incomplete execution.

## Testing

Shell tests use fakes and fixtures to cover:

- diagnostic collection enabled by default in `run-load-test.sh`;
- each negative diagnostic switch disables only its collector;
- preparation forwards the negative switches to every smoke attempt;
- the exact reset, build, readiness, stabilization, smoke, settling, and quiescence order;
- readiness success and the 120-second timeout without real sleeping;
- one successful smoke;
- retry of a partial but functionally correct smoke;
- immediate abort for missing or contradictory outcomes, HTTP failure, replay violation, malformed report, infrastructure failure, or instrumentation failure;
- at most three attempts;
- three consecutive zero-lag readings and failure after any nonzero or unreadable reading;
- distinction between a completed invalid report and an operational runner failure;
- use of the same quiescence check by preparation and the ordinary runner;
- the stack remaining available after success and failure;
- exit status propagation.

After automated tests, run `bash -n` on changed scripts, the existing load-test shell suite, `go test ./...`, and `git diff --check`. Finish with one real preparation run and one short measured diagnostic run. No 15-minute validation is required for this automation change.

## Out of scope

- Starting or chaining measured profiles from the preparation command.
- Automatically switching between A and B code variants.
- Long performance runs or performance tuning.
- A generic experiment framework or manifest.
- Archiving Compose state, container logs, Git metadata, or image inventories.
- Exact proof that the SPI has no database-only latent work.
- Snapshotting or restoring Docker volumes.
- Optimizing certificate generation or the fixed profile durations.
