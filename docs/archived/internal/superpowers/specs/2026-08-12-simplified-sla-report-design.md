# Simplified SLA Report Design

## Goal

Make `sla-report.json` an immediately readable decision and aggregate workload report. Keep detailed timestamp correlation and per-message audit in the four CSV files under `events/`.

This change replaces the current report contract directly. It does not retain aliases or compatibility with the previous JSON shape, does not change workload generation, and does not add historical report regeneration.

## Report responsibilities

The report answers four questions:

1. Is the run valid?
2. Did the load-tool generate the contracted rate of original payments?
3. What traffic and business outcome did each scenario produce?
4. What global replay load and active-window performance were observed?

The report does not repeat the run manifest, detailed event history, or configuration already preserved in `run-window.json` and `inputs/profile.json`.

## Contract

```json
{
  "valid": true,
  "generation": {
    "target_tps": 100,
    "expected": 1000,
    "started": 1000,
    "actual_tps": 100,
    "violations": 0
  },
  "scenarios": [
    {
      "name": "happy-path",
      "share": 0.8,
      "traffic": {
        "payments": {
          "started": 1000,
          "accepted": 1000
        },
        "pacs002": {
          "started": 1000,
          "accepted": 1000
        }
      },
      "outcome": {
        "expected": {
          "status": "ACSC",
          "reason_codes": []
        },
        "matched": 1000,
        "missing": 0,
        "contradictory": 0
      },
      "performance": {
        "within_threshold": 802,
        "after_threshold": 0,
        "latency_ms": {
          "p50": 124.608,
          "p95": 203.816,
          "p99": 250.053,
          "max": 279.828
        }
      },
      "violations": 0
    }
  ],
  "replays": {
    "pacs008": {
      "started": 126,
      "accepted": 126,
      "violations": 0
    },
    "pacs002": {
      "started": 126,
      "accepted": 126,
      "violations": 0
    }
  },
  "performance": {
    "threshold_ms": 1000,
    "active_tps": {
      "payments": 100,
      "pacs008_replays": 2.6,
      "pacs002_replays": 2.5,
      "payer_notifications": 98.9
    },
    "payer_notifications_after_active": 11,
    "latency_ms": {
      "p50": 124.519,
      "p95": 204.904,
      "p99": 256.561,
      "max": 317.628
    }
  }
}
```

`started` means that the load-tool started an HTTP attempt. `accepted` means that the attempt received an HTTP `2xx` response. This distinction is preserved for payments, original `pacs.002` messages and replays. An HTTP acceptance is not a business outcome: the asynchronous payer notification remains the authoritative observable outcome.

## Scope and aggregation

`generation` concerns only original payments in the semi-open active window. `expected` is `target_tps × active duration`, `started` is the number of original HTTP attempts in that window, and `actual_tps` is `started ÷ active duration`. Its violations retain the existing generation checks, including deficit or excess and originals outside the authoritative generation window.

Scenario traffic and correctness cover the entire run, including warmup and drain observations. Payment attempts are assigned by the scenario recorded in `pacs008-starts.csv`. Original `pacs.002` attempts are assigned by the scenario recorded in `pacs002-starts.csv`.

`outcome.matched`, `missing` and `contradictory` count logical payments, not notification frames. At-least-once delivery means one or more compatible payer notifications count as one matched result. No corresponding notification is missing. Any delivery with a status or reason-code set incompatible with the scenario is contradictory, even if another delivery is compatible. A payment can therefore be both matched and contradictory, and the contradiction invalidates the run.

Scenario `violations` aggregates that scenario's payment HTTP failures, original `pacs.002` HTTP/deadline failures, missing outcomes and contradictory outcomes. It remains a validation count, not a count of unique payments.

Replay selection and accounting remain global because replay shares apply to their respective global populations. Replay `started`, `accepted` and `violations` qualify only aggregate workload integrity: selected and started counts must match, and every started attempt must receive HTTP `2xx`. Identity, sender, scenario, timing and byte equality are generator properties covered by its focused tests rather than revalidated from its own evidence.

Performance metrics remain active-window-only. `active_tps` does not publish a rate for original `pacs.002`: those messages are causal work whose timing depends on SPI progress, not part of the offered-throughput contract. Their total started/accepted counts remain under each scenario. Global and per-scenario latency use the earliest compatible payer notification for each accepted original payment started in the active window. Repeated compatible deliveries do not inflate rates or latency populations. Floating-point metrics are rounded to three decimal places in the published JSON.

## Validity and runner behavior

`valid` is the public run decision. It is true exactly when generation, every scenario and both replay types have zero violations. Performance thresholds remain observations in this task; they do not introduce final performance gates.

The shell runner validates that the report is a JSON object containing a boolean `valid`. It returns success only when `valid` is true. It no longer recursively discovers every field named `violations`.

## Removed structure

The replacement removes these top-level blocks and aliases from the current JSON:

- `run`;
- `transactions`;
- `status_messages`;
- `load_generation`;
- `throughput_per_second`;
- `payer_notification_latency_ms`;
- `diagnostics`;
- duplicate throughput aliases such as `started` and `original_payments_started`;
- nested HTTP expectation and notification-delivery configuration copied from the profile;
- separate status- and reason-mismatch counters in favor of logical `contradictory` outcomes.

The authoritative run timing remains in `run-window.json`; profile values and expectations remain in `inputs/profile.json`; raw evidence remains in `events/*.csv`.

## Verification

Focused report tests protect generation, scenario traffic, at-least-once outcomes, per-scenario original `pacs.002`, global replays, active-window metrics, rounding and `valid`. Runner tests protect strict boolean parsing and public nonzero exit status for `valid: false`. The full Go and shell suites must remain green, followed by `bash -n` and `git diff --check`.
