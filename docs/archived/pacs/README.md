# PACS reference material

This directory contains external message specifications kept as domain reference material. It is not part of the project's canonical design documentation and the runtime does not load these files dynamically.

- The files at the directory root are generic ISO 20022 `pacs.002.001.15` and `pacs.008.001.13` schemas plus the spreadsheets consulted during development.
- [`v5.10.1/`](v5.10.1/) is a snapshot of the Banco Central do Brasil SPI message catalog, schemas and release notes for that version.

These artifacts help trace vocabulary and message-shape decisions, but they may not describe every simplification made by this MVP. Current behavior is defined by the implementation, tests and [system design](../design.md).
