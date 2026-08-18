#!/usr/bin/env python3

import csv
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


PLANNED_ORIGINALS = 1_250
QUALIFIED = 0
RETRYABLE = 10
INVALID = 20
EXPECTED_OUTCOMES = {
    "happy-path": ("ACSC", []),
    "insufficient-funds": ("RJCT", ["AM04"]),
}


class InvalidReport(ValueError):
    pass


@dataclass(frozen=True)
class ScenarioResult:
    started: int
    accepted: int
    pacs002_started: int
    pacs002_accepted: int
    matched: int
    missing: int
    contradictory: int
    violations: int


def require_object(value: Any, location: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise InvalidReport(f"{location} must be an object")
    return value


def require_counter(container: dict[str, Any], field: str, location: str) -> int:
    value = container.get(field)
    if type(value) is not int or value < 0:
        raise InvalidReport(f"{location}.{field} must be a non-negative integer")
    return value


def require_scenario(scenario: dict[str, Any], name: str) -> ScenarioResult:
    traffic = require_object(scenario.get("traffic"), f"scenarios[{name}].traffic")
    payments = require_object(
        traffic.get("payments"), f"scenarios[{name}].traffic.payments"
    )
    pacs002 = require_object(
        traffic.get("pacs002"), f"scenarios[{name}].traffic.pacs002"
    )
    outcome = require_object(scenario.get("outcome"), f"scenarios[{name}].outcome")
    expected = require_object(
        outcome.get("expected"), f"scenarios[{name}].outcome.expected"
    )

    payments_started = require_counter(
        payments, "started", f"scenarios[{name}].traffic.payments"
    )
    payments_accepted = require_counter(
        payments, "accepted", f"scenarios[{name}].traffic.payments"
    )
    pacs002_started = require_counter(
        pacs002, "started", f"scenarios[{name}].traffic.pacs002"
    )
    pacs002_accepted = require_counter(
        pacs002, "accepted", f"scenarios[{name}].traffic.pacs002"
    )
    matched = require_counter(outcome, "matched", f"scenarios[{name}].outcome")
    missing = require_counter(outcome, "missing", f"scenarios[{name}].outcome")
    contradictory = require_counter(
        outcome, "contradictory", f"scenarios[{name}].outcome"
    )
    violations = require_counter(scenario, "violations", f"scenarios[{name}]")

    expected_status, expected_reasons = EXPECTED_OUTCOMES[name]
    if expected.get("status") != expected_status:
        raise InvalidReport(
            f"scenarios[{name}].outcome.expected.status must be {expected_status}"
        )
    if expected.get("reason_codes") != expected_reasons:
        raise InvalidReport(
            f"scenarios[{name}].outcome.expected.reason_codes must be {expected_reasons}"
        )

    if payments_accepted > payments_started:
        raise InvalidReport(f"scenarios[{name}] accepts more payments than it starts")
    expected_pacs002 = payments_started if name == "happy-path" else 0
    if pacs002_started != expected_pacs002 or pacs002_accepted != expected_pacs002:
        raise InvalidReport(
            f"scenarios[{name}] has unexpected original PACS.002 traffic: "
            f"started={pacs002_started}, accepted={pacs002_accepted}, "
            f"expected={expected_pacs002}"
        )
    if missing != 0 or contradictory != 0:
        raise InvalidReport(f"scenarios[{name}] has incomplete or contradictory outcomes")

    return ScenarioResult(
        started=payments_started,
        accepted=payments_accepted,
        pacs002_started=pacs002_started,
        pacs002_accepted=pacs002_accepted,
        matched=matched,
        missing=missing,
        contradictory=contradictory,
        violations=violations,
    )


def require_replay(replays: dict[str, Any], message_type: str) -> None:
    replay = require_object(replays.get(message_type), f"replays.{message_type}")
    started = require_counter(replay, "started", f"replays.{message_type}")
    accepted = require_counter(replay, "accepted", f"replays.{message_type}")
    violations = require_counter(replay, "violations", f"replays.{message_type}")
    if accepted != started or violations != 0:
        raise InvalidReport(f"replays.{message_type} is incomplete or has violations")


def read_csv_rows(path: Path, required_fields: set[str]) -> list[dict[str, str]]:
    try:
        with path.open(encoding="utf-8", newline="") as csv_file:
            reader = csv.DictReader(csv_file)
            if reader.fieldnames is None or not required_fields.issubset(reader.fieldnames):
                required = ", ".join(sorted(required_fields))
                raise InvalidReport(f"{path} is missing required columns: {required}")
            rows: list[dict[str, str]] = []
            for row_number, row in enumerate(reader, start=2):
                if any(row.get(field) is None for field in required_fields):
                    raise InvalidReport(f"{path}:{row_number} is missing required values")
                rows.append(row)
            return rows
    except (OSError, csv.Error) as error:
        raise InvalidReport(f"cannot read {path}: {error}") from error


def require_original_evidence(
    run_dir: Path, scenario_results: dict[str, ScenarioResult]
) -> dict[str, tuple[str, str]]:
    path = run_dir / "events" / "pacs008-starts.csv"
    rows = read_csv_rows(
        path,
        {"end_to_end_id", "payer_ispb", "http_status", "scenario_name"},
    )
    scenario_rows = {name: 0 for name in EXPECTED_OUTCOMES}
    scenario_accepted = {name: 0 for name in EXPECTED_OUTCOMES}
    timed_out: dict[str, tuple[str, str]] = {}
    seen_ids: set[str] = set()

    for row_number, row in enumerate(rows, start=2):
        end_to_end_id = row["end_to_end_id"]
        payer_ispb = row["payer_ispb"]
        scenario_name = row["scenario_name"]
        if not end_to_end_id or end_to_end_id in seen_ids:
            raise InvalidReport(
                f"{path}:{row_number} has an empty or duplicate end_to_end_id"
            )
        if not payer_ispb:
            raise InvalidReport(f"{path}:{row_number} has an empty payer_ispb")
        if scenario_name not in EXPECTED_OUTCOMES:
            raise InvalidReport(
                f"{path}:{row_number} has unsupported scenario {scenario_name!r}"
            )
        try:
            http_status = int(row["http_status"])
        except ValueError as error:
            raise InvalidReport(
                f"{path}:{row_number} has invalid http_status {row['http_status']!r}"
            ) from error

        seen_ids.add(end_to_end_id)
        scenario_rows[scenario_name] += 1
        if 200 <= http_status < 300:
            scenario_accepted[scenario_name] += 1
        elif http_status == 0:
            timed_out[end_to_end_id] = (scenario_name, payer_ispb)
        else:
            raise InvalidReport(
                f"{path}:{row_number} records explicit non-2xx HTTP status {http_status}"
            )

    for name, result in scenario_results.items():
        if scenario_rows[name] != result.started:
            raise InvalidReport(
                f"scenarios[{name}] report started={result.started}, "
                f"but PACS.008 evidence has {scenario_rows[name]} rows"
            )
        if scenario_accepted[name] != result.accepted:
            raise InvalidReport(
                f"scenarios[{name}] report accepted={result.accepted}, "
                f"but PACS.008 evidence has {scenario_accepted[name]} 2xx responses"
            )

    return timed_out


def parse_reason_codes(raw_value: str, path: Path, row_number: int) -> list[str]:
    try:
        reason_codes = json.loads(raw_value)
    except json.JSONDecodeError as error:
        raise InvalidReport(
            f"{path}:{row_number} has invalid reason_codes JSON"
        ) from error
    if not isinstance(reason_codes, list) or not all(
        isinstance(reason, str) for reason in reason_codes
    ):
        raise InvalidReport(f"{path}:{row_number} reason_codes must be a string array")
    return reason_codes


def require_timeout_outcomes(
    run_dir: Path, timed_out: dict[str, tuple[str, str]]
) -> None:
    path = run_dir / "events" / "notifications.csv"
    rows = read_csv_rows(
        path,
        {"end_to_end_id", "ispb", "event_type", "status_code", "reason_codes"},
    )
    matching_notifications = {end_to_end_id: 0 for end_to_end_id in timed_out}

    for row_number, row in enumerate(rows, start=2):
        reason_codes = parse_reason_codes(row["reason_codes"], path, row_number)
        end_to_end_id = row["end_to_end_id"]
        timeout = timed_out.get(end_to_end_id)
        if timeout is None:
            continue
        scenario_name, payer_ispb = timeout
        if row["event_type"] != "pacs002_received" or row["ispb"] != payer_ispb:
            continue

        expected_status, expected_reasons = EXPECTED_OUTCOMES[scenario_name]
        if row["status_code"] != expected_status or reason_codes != expected_reasons:
            raise InvalidReport(
                f"timed-out payment {end_to_end_id} has contradictory payer PACS.002"
            )
        matching_notifications[end_to_end_id] += 1

    missing_notifications = [
        end_to_end_id
        for end_to_end_id, count in matching_notifications.items()
        if count == 0
    ]
    if missing_notifications:
        raise InvalidReport(
            "timed-out payments have no expected payer PACS.002: "
            + ", ".join(missing_notifications)
        )


def require_scenario_consistency(
    scenario_results: dict[str, ScenarioResult],
    timed_out: dict[str, tuple[str, str]],
) -> None:
    timeouts_by_scenario = {name: 0 for name in EXPECTED_OUTCOMES}
    for scenario_name, _payer_ispb in timed_out.values():
        timeouts_by_scenario[scenario_name] += 1

    for name, result in scenario_results.items():
        timeout_count = timeouts_by_scenario[name]
        if result.started - result.accepted != timeout_count:
            raise InvalidReport(
                f"scenarios[{name}] HTTP shortfall does not match timeout evidence"
            )
        if result.matched != result.accepted:
            raise InvalidReport(
                f"scenarios[{name}] matched outcomes do not match accepted HTTP responses"
            )
        if result.violations != timeout_count:
            raise InvalidReport(
                f"scenarios[{name}] violations do not match timeout evidence"
            )


def qualify(run_dir: Path) -> int:
    report_path = run_dir / "sla-report.json"
    try:
        with report_path.open(encoding="utf-8") as report_file:
            document = json.load(report_file)
    except (OSError, json.JSONDecodeError) as error:
        raise InvalidReport(f"cannot read {report_path}: {error}") from error

    root = require_object(document, "report")
    if type(root.get("valid")) is not bool:
        raise InvalidReport("report.valid must be a boolean")

    scenarios_value = root.get("scenarios")
    if not isinstance(scenarios_value, list):
        raise InvalidReport("report.scenarios must be an array")
    if len(scenarios_value) != len(EXPECTED_OUTCOMES):
        raise InvalidReport("report.scenarios must contain exactly the two smoke scenarios")

    scenarios: dict[str, dict[str, Any]] = {}
    for index, scenario_value in enumerate(scenarios_value):
        scenario = require_object(scenario_value, f"report.scenarios[{index}]")
        name = scenario.get("name")
        if name not in EXPECTED_OUTCOMES or name in scenarios:
            raise InvalidReport(f"report.scenarios[{index}].name is unsupported or duplicated")
        scenarios[name] = scenario

    scenario_results = {
        name: require_scenario(scenarios[name], name) for name in EXPECTED_OUTCOMES
    }
    total_originals = sum(result.started for result in scenario_results.values())

    replays = require_object(root.get("replays"), "report.replays")
    require_replay(replays, "pacs008")
    require_replay(replays, "pacs002")

    timed_out = require_original_evidence(run_dir, scenario_results)
    require_timeout_outcomes(run_dir, timed_out)
    require_scenario_consistency(scenario_results, timed_out)

    if total_originals > PLANNED_ORIGINALS:
        raise InvalidReport(
            f"smoke started {total_originals} originals, above planned {PLANNED_ORIGINALS}"
        )
    if total_originals < PLANNED_ORIGINALS or timed_out:
        print(
            f"Smoke is functionally correct but retryable: "
            f"started={total_originals} planned={PLANNED_ORIGINALS} "
            f"timeouts={len(timed_out)}."
        )
        return RETRYABLE

    print(f"Smoke qualified: started={total_originals} planned={PLANNED_ORIGINALS}.")
    return QUALIFIED


def main() -> int:
    if len(sys.argv) != 2:
        print(f"Usage: {Path(sys.argv[0]).name} RUN_DIR", file=sys.stderr)
        return INVALID
    try:
        return qualify(Path(sys.argv[1]))
    except InvalidReport as error:
        print(f"Smoke qualification failed: {error}", file=sys.stderr)
        return INVALID


if __name__ == "__main__":
    raise SystemExit(main())
