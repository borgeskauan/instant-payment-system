# Load-test run-bundle layout

## Purpose

Make each load-test result directory readable by separating configuration inputs, raw temporal events, operational logs and optional diagnostic datasets. Preserve every per-payment and per-message record required for later timestamp correlation, charting and audit.

This change reorganizes artifacts; it does not reduce functional evidence or introduce different retention policies for successful and failed runs.

## Final layout

```text
<run>/
├── run-window.json
├── sla-report.json
├── inputs/
│   ├── profile.json
│   └── execution-plan.json
├── events/
│   ├── pacs008-starts.csv
│   ├── pacs002-starts.csv
│   ├── notifications.csv
│   └── replays.csv
├── logs/
│   ├── prepare-environment.log
│   ├── loadtool.log
│   ├── spi-trace.log
│   ├── postgres-statements.log
│   └── jfr/
│       ├── kafka-producer.log
│       ├── spi.log
│       └── notification-gateway.log
└── diagnostics/
    ├── spi-trace.csv
    ├── postgres-statements.csv
    └── jfr/
        ├── kafka-producer.jfr
        ├── spi.jfr
        └── notification-gateway.jfr
```

`run-window.json` remains the root manifest and authoritative temporal window. `sla-report.json` remains the immediately visible aggregate result. It is absent when execution does not reach successful report publication.

## Artifact boundaries

### Inputs

`inputs/profile.json` is the byte-identical declarative profile snapshot. `inputs/execution-plan.json` is the normalized plan emitted by authoritative Go validation and consumed by automatic environment preparation. Both remain in the final bundle as execution evidence.

### Events

All raw records remain available:

- `pacs008-starts.csv` records original payment HTTP attempts;
- `pacs002-starts.csv` records original status HTTP attempts;
- `notifications.csv` records externally observed PACS.008 and PACS.002 notifications;
- `replays.csv` records repeated PACS.008 and PACS.002 attempts.

The existing CSV schemas and full-run correctness semantics remain unchanged. Only paths and the ambiguous filenames `starts.csv`, `status-starts.csv` and `events.csv` change.

### Logs

Textual operational output belongs exclusively under `logs/`. `prepare-environment.log` is created when environment preparation starts. `loadtool.log` is created when the Go run starts and captures both standard output and standard error. Their retention does not depend on success, violations or failure.

Opt-in diagnostic commands write their operational logs under `logs/`, including component-specific JFR command logs. If a phase never starts, its log may be absent; the runner does not create empty placeholder artifacts.

### Diagnostics

Machine-analyzable or binary diagnostic outputs belong under `diagnostics/`: SPI trace CSV, PostgreSQL statement CSV and JFR recordings. They exist only when their corresponding diagnostic option is enabled and reaches artifact collection.

Certificates remain ephemeral execution material. They may exist while a run is active but are removed by cleanup and are not part of the final bundle.

## Runtime ownership and data flow

The typed Go `runbundle.Layout` is the canonical path resolver for both simulation and reporting. It resolves root files, input snapshots and event files. The report consumes only the event paths provided by this layout and writes `sla-report.json` atomically at the root.

The shell runner prepares `inputs/` and `logs/`, invokes environment preparation with the run directory, captures Go output under `logs/loadtool.log`, and routes opt-in diagnostic logs and outputs to their respective boundaries. `prepare-environment.sh` resolves `inputs/execution-plan.json` from the fixed bundle layout rather than accepting an arbitrary plan path.

`run-window.json` records the new relative artifact paths. Its profile references point to `inputs/profile.json` and `inputs/execution-plan.json`; its event references point to the four files under `events/`.

The external result directory convention remains `results/<run-tag>/<timestamp>/`.

## Failure behavior

There is one retention policy for every run: never delete a produced artifact based on outcome. A failed run preserves everything produced up to its failure point. Artifacts for phases that never started are naturally absent.

Existing guarantees remain:

- authoritative profile validation completes before the result directory is created;
- environment-preparation failure prevents load generation;
- simulation or report failure does not publish `sla-report.json`;
- a run directory containing previous generated output is not reused;
- diagnostic collection after Go failure continues to preserve the original Go exit code.

## Current artifact contract

No migration or fallback reading of a former directory layout or artifact schema is supported. The load-tool reads only the current bundle paths, run-window schema and CSV headers.

After automated verification succeeds, the existing contents of `load-test/results/` will be removed. One short functional smoke run will then validate the new layout through the public runner.

## Verification

Automated tests will prove that:

- the typed bundle resolves every required path to the new boundary;
- prepared-run validation requires `inputs/profile.json` and rejects existing generated output at the new paths;
- simulation writes all four renamed event files under `events/`;
- reporting reads those exact files and publishes only the root `sla-report.json`;
- environment preparation reads `inputs/execution-plan.json`;
- the runner copies both input snapshots, captures preparation and Go logs under `logs/`, and records the new paths in `run-window.json`;
- `loadtool.log` captures standard output and standard error;
- failed runs preserve partial event and log artifacts without conditional cleanup;
- optional diagnostic logs and datasets are routed to `logs/` and `diagnostics/` respectively;
- no final bundle contains certificates or former root/go-loadtool artifact paths.

The complete load-test shell suite, `go test ./...`, Bash syntax checks and `git diff --check` remain required. All changes remain uncommitted for review through the working-tree diff.
