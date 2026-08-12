# Current-only load-tool artifact contract

## Purpose

Remove residual compatibility that existed only to read artifacts produced by earlier load-tool contracts. The load-tool will behave as though historical report regeneration had never been a requirement: it supports the current bundle and artifact schemas only.

This cleanup does not change workload generation, business validation, report contents, the public runner, CSV retention or the current run-bundle layout.

## Supported contract

The report path accepts exactly the current bundle:

- `run-window.json` uses the current `schema_version` and its four authoritative experiment timestamps;
- `events/pacs008-starts.csv` includes `pacs008_replay_selected`;
- `events/pacs002-starts.csv` uses the current status-start header;
- `events/notifications.csv` uses the current status/reason-aware header;
- `events/replays.csv` uses `sender_ispb` and the current replay header;
- all four event files are required, including for workloads whose replay files contain only their headers.

No former schema, header, missing-artifact combination or legacy field receives a dedicated migration, fallback or error contract.

## Code simplification

Remove:

- the schema-zero branch and legacy timestamp fields from `runwindow`;
- legacy start and replay headers and their parsing branches from `events`;
- the compatibility-only `Replay.PayerISPB` field and writer fallback;
- report entry points or conditional reads that allow omission of current event populations;
- tests and fixtures whose only purpose is accepting or explicitly rejecting former formats.

Keep:

- strict parsing of the current schemas as ordinary input validation;
- simulation writing all four CSVs;
- reporting reading those persisted CSVs after simulation;
- atomic publication of `sla-report.json`;
- auditability and timestamp correlation from the retained artifacts.

## Testing

Tests will describe only current behavior:

- writer/reader round trips use the current headers and fields;
- the current run window resolves against the current profile;
- the report requires and consumes all four current event files;
- existing workload characterization and semantic report tests remain green.

There will be no test named for a legacy or historical format and no fixture preserving one. Existing general malformed-input coverage remains only where it protects current parser correctness.

## Documentation

Current task documentation will say that stored artifacts support audit and report generation during the run. It will not claim historical/offline report regeneration or compatibility with artifacts from previous load-tool contracts.
