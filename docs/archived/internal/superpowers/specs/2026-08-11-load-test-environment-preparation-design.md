# Isolated load-test environment preparation

## Purpose

Extract Kafka quiescence checks and deterministic fund provisioning from the public load-test runner into one internal, testable environment-preparation command. The runner remains the public entry point and only orchestrates preparation before invoking the Go load tool.

The preparation qualifies only the conditions it can observe. Zero Kafka lag is a best-effort heuristic and is not presented as proof that the complete system is quiescent.

## Public behavior

`run-load-test.sh` remains the only public command. Its preparation sequence becomes:

```text
resolve and validate profile
create profile.json and execution-plan.json
prepare certificates
run prepare-environment.sh --run-dir <directory>
start optional diagnostics
run go-loadtool run --run-dir <directory>
```

Environment preparation is automatic and mandatory. The unused `--provision-funds` and `--no-provision-funds` flags, their runner state and the conditional skip path are removed. Every measured run provisions the initial balances resolved from its profile.

## Internal command

`load-test/scripts/prepare-environment.sh` is an internal executable, not an additional public load-test interface. It accepts only `--run-dir` and resolves `execution-plan.json` from the fixed run-bundle layout.

It performs two ordered operations:

1. `check_kafka_quiescence` reads the existing SPI-input and notification-gateway consumer-group lags. Nonzero or unreadable lag fails the command before fund provisioning.
2. `provision_profile_funding` reads the resolved scenario participants, balances and reset behavior from `execution-plan.json`, then provisions them through the existing SPI administrative funding script. Any provisioning failure aborts before workload generation.

The operations remain separate functions with separate focused tests even though the runner makes one command invocation.

## Execution-plan boundary

The profile remains the declarative source of intent. `go-loadtool validate-profile` remains the authoritative resolver and produces the normalized `execution-plan.json` before environment preparation starts.

The preparation script performs only the structural parsing needed to consume these already-validated fields. It does not duplicate scenario semantics, funding calculations, range validation or profile validation in shell or Python.

The persisted plan serves three purposes:

- the runner consumes resolved timings and participant metadata required by its remaining orchestration;
- environment preparation consumes resolved provisioning instructions;
- the result bundle preserves the exact resolved input as execution evidence.

`go-loadtool run` continues to read `profile.json` in this incremental slice. Making it consume the resolved execution plan is a possible later simplification, not part of this extraction.

## Preserved scope boundaries

Certificate generation, Grafana links, SPI trace, JFR and PostgreSQL statement diagnostics remain in their current runner paths. Because certificate generation remains outside environment preparation, the runner still consumes participant allocation and derives PSP certificate identities. This slice does not claim to remove that separate topology coupling.

The existing `scripts/provision-funds.sh` remains the SPI administrative API adapter. Environment preparation owns when and how it is invoked; the runner no longer knows funding balances, reset behavior or provisioning loops.

Kafka container, broker, topic and consumer-group defaults move out of the runner and into the internal preparation command. Test overrides remain internal environment variables rather than public runner flags.

## Failures and logs

The runner captures the preparation command's exit status. A preparation failure prevents optional diagnostics and `go-loadtool run` from starting and is returned as the public runner failure.

The command writes its combined output to `prepare-environment.log` in the run directory. This replaces `provision-funds.log`; no additional JSON artifact is introduced.

Failure ordering is explicit:

- a missing, malformed or structurally unusable execution plan fails before Docker or SPI calls;
- unreadable or nonzero Kafka lag fails before fund provisioning;
- a funding API failure fails before workload generation.

## Verification

Focused shell tests will prove that:

- the internal command requires a usable `execution-plan.json` before external calls;
- zero Kafka lag reaches provisioning;
- nonzero or unreadable lag does not invoke the funding adapter;
- provisioning receives the participants, balances and reset behavior from the resolved plan;
- a funding failure propagates and prevents workload execution;
- the public runner invokes environment preparation once with the run directory;
- both removed provisioning flags are rejected;
- the runner no longer contains Kafka topic/group commands or funding-specific loops;
- the internal command remains testable with fake Docker and funding adapters.

The complete shell suite, Go load-tool tests, Bash syntax checks and `git diff --check` remain required. Changes stay uncommitted for review through `git diff`.
