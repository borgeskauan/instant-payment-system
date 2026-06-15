#!/usr/bin/env python3

import argparse
import csv
import json
import re
import shutil
import subprocess
from pathlib import Path
from statistics import pstdev


OUTPUT_PATTERNS = {
    "succeeded": re.compile(r"transactions succeeded:\s*(\d+)"),
    "missed": re.compile(r"transactions missed SLA:\s*(\d+)"),
    "late": re.compile(r"transactions late:\s*(\d+)"),
    "never": re.compile(r"transactions never arrived:\s*(\d+)"),
    "p50": re.compile(r"total transaction duration p50:\s*([0-9.]+)s"),
    "p95": re.compile(r"total transaction duration p95:\s*([0-9.]+)s"),
    "p99": re.compile(r"total transaction duration p99:\s*([0-9.]+)s"),
    "hot_pacing_skips": re.compile(r"hot pacing skips:\s*(\d+)"),
    "cold_pacing_skips": re.compile(r"cold pacing skips:\s*(\d+)"),
}

CONTAINER_COLUMNS = [
    "host",
    "kafka",
    "spi",
    "kafka-producer",
    "notification-gateway",
]


def read_json(path):
    if not path.exists():
        return {}

    with path.open() as file:
        return json.load(file)


def number(value, default=0):
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def metric(summary, name, field, default=0):
    metric_data = summary.get("metrics", {}).get(name, {})
    return metric_data.get(field, metric_data.get("values", {}).get(field, default))


def percentile(summary, name, field, default=0):
    metric_data = summary.get("metrics", {}).get(name, {})
    return metric_data.get(
        field,
        metric_data.get("values", {}).get(field, metric_data.get("percentiles", {}).get(field, default)),
    )


def parse_output(path):
    values = {key: 0 for key in OUTPUT_PATTERNS}
    values["p99"] = None
    if not path.exists():
        return values

    text = path.read_text(errors="replace")
    for key, pattern in OUTPUT_PATTERNS.items():
        match = pattern.search(text)
        if not match:
            continue

        values[key] = number(match.group(1)) if key.startswith("p") else int(match.group(1))

    return values


def read_cpu_stats(path, name_column, value_column="cpu_percent"):
    values = {}
    if not path.exists():
        return values

    with path.open() as file:
        for row in csv.DictReader(file):
            name = row.get(name_column)
            if not name:
                continue

            values.setdefault(name, []).append(number(row.get(value_column)))

    return {
        name: {
            "avg": sum(samples) / len(samples),
            "max": max(samples),
        }
        for name, samples in values.items()
        if samples
    }


def read_lag(path):
    max_by_group = {}
    if not path.exists():
        return max_by_group

    with path.open() as file:
        for row in csv.DictReader(file):
            group = row.get("group") or row.get("consumer_group")
            if not group:
                continue

            lag = int(number(row.get("lag") or row.get("total_lag")))
            max_by_group[group] = max(max_by_group.get(group, 0), lag)

    return max_by_group


def jfr_size_mb(path):
    if not path.exists():
        return 0

    return path.stat().st_size / 1024 / 1024


def run_row(run_dir, active_seconds):
    summary = read_json(run_dir / "summary.json")
    output = parse_output(run_dir / "output.txt")
    containers = read_cpu_stats(run_dir / "system-stats.csv", "name")
    processes = read_cpu_stats(run_dir / "process-stats.csv", "command")
    lag = read_lag(run_dir / "kafka-lag.csv")
    jfr_file = run_dir / "spi-load-test.jfr"

    p99 = output["p99"]
    if p99 is None:
        raw_p99 = percentile(summary, "total_transaction_duration", "p(99)", None)
        p99 = raw_p99 / 1000 if raw_p99 is not None else None

    row = {
        "run": run_dir.name,
        "started": metric(summary, "transactions_started", "count"),
        "active_rate": metric(summary, "transactions_started", "count") / active_seconds,
        "hot_rate": metric(summary, "hot_transactions_started", "count") / active_seconds,
        "cold_rate": metric(summary, "cold_transactions_started", "count") / active_seconds,
        "hot_interval_p95": percentile(summary, "hot_start_interval", "p(95)", None),
        "cold_interval_p95": percentile(summary, "cold_start_interval", "p(95)", None),
        "hot_delay_p95": percentile(summary, "hot_start_delay", "p(95)", None),
        "cold_delay_p95": percentile(summary, "cold_start_delay", "p(95)", None),
        "hot_pacing_skips": output["hot_pacing_skips"] or metric(summary, "hot_pacing_skips", "count"),
        "cold_pacing_skips": output["cold_pacing_skips"] or metric(summary, "cold_pacing_skips", "count"),
        "summary_rate": metric(summary, "transactions_started", "rate"),
        "dropped": metric(summary, "dropped_iterations", "count"),
        "backpressure": metric(summary, "backpressure_skips", "count"),
        "succeeded": output["succeeded"] or metric(summary, "transactions_succeeded", "count"),
        "missed": output["missed"] or metric(summary, "transactions_missed_sla", "count"),
        "late": output["late"] or metric(summary, "transactions_late", "count"),
        "never": output["never"] or metric(summary, "transactions_never_arrived", "count"),
        "p50": output["p50"] or percentile(summary, "total_transaction_duration", "p(50)") / 1000,
        "p95": output["p95"] or percentile(summary, "total_transaction_duration", "p(95)") / 1000,
        "p99": p99,
        "spi_lag": lag.get("spi-consumer-group", 0),
        "notification_lag": lag.get("notification-gateway-group", 0),
        "jfr_mb": jfr_size_mb(jfr_file),
        "jfr": jfr_file.exists(),
    }

    for name in CONTAINER_COLUMNS:
        stats = containers.get(name, {})
        row[f"{name}_avg"] = stats.get("avg", 0)
        row[f"{name}_max"] = stats.get("max", 0)

    k6_stats = processes.get("k6", {})
    row["k6_avg"] = k6_stats.get("avg", 0)
    row["k6_max"] = k6_stats.get("max", 0)

    return row


def collect_rows(summary_dir, active_seconds):
    run_dirs = sorted(path for path in summary_dir.iterdir() if path.is_dir())
    return [run_row(run_dir, active_seconds) for run_dir in run_dirs if (run_dir / "summary.json").exists()]


def print_table(rows):
    print(
        "run              tx/s hot/s cold/s hotInt95 coldInt95 hotDelay95 coldDelay95 hotSkip coldSkip "
        "started dropped backpres succeeded p50    p95    p99 missed late never "
        "spiLag notifLag hostAvg hostMax kafkaAvg kafkaMax spiAvg spiMax k6Avg k6Max jfrMB"
    )

    for row in rows:
        print(
            f"{row['run']} "
            f"{row['active_rate']:7.2f} "
            f"{row['hot_rate']:5.1f} "
            f"{row['cold_rate']:6.1f} "
            f"{format_ms(row['hot_interval_p95'], 8)} "
            f"{format_ms(row['cold_interval_p95'], 9)} "
            f"{format_ms(row['hot_delay_p95'], 10)} "
            f"{format_ms(row['cold_delay_p95'], 11)} "
            f"{row['hot_pacing_skips']:7.0f} "
            f"{row['cold_pacing_skips']:8.0f} "
            f"{row['started']:7.0f} "
            f"{row['dropped']:7.0f} "
            f"{row['backpressure']:8.0f} "
            f"{row['succeeded']:9.0f} "
            f"{row['p50']:6.2f} "
            f"{row['p95']:6.2f} "
            f"{format_optional(row['p99'], 6)} "
            f"{row['missed']:6.0f} "
            f"{row['late']:5.0f} "
            f"{row['never']:5.0f} "
            f"{row['spi_lag']:6.0f} "
            f"{row['notification_lag']:8.0f} "
            f"{row['host_avg']:7.2f} "
            f"{row['host_max']:7.2f} "
            f"{row['kafka_avg']:8.2f} "
            f"{row['kafka_max']:8.2f} "
            f"{row['spi_avg']:6.2f} "
            f"{row['spi_max']:6.2f} "
            f"{row['k6_avg']:6.1f} "
            f"{row['k6_max']:6.1f} "
            f"{row['jfr_mb']:5.1f}"
        )


def print_aggregates(rows):
    print("\nAGGREGATES")
    keys = [
        "active_rate",
        "hot_rate",
        "cold_rate",
        "hot_interval_p95",
        "cold_interval_p95",
        "hot_delay_p95",
        "cold_delay_p95",
        "hot_pacing_skips",
        "cold_pacing_skips",
        "started",
        "dropped",
        "backpressure",
        "succeeded",
        "p50",
        "p95",
        "missed",
        "late",
        "never",
        "spi_lag",
        "notification_lag",
        "host_avg",
        "host_max",
        "kafka_avg",
        "spi_avg",
        "k6_avg",
    ]

    for key in keys:
        values = [row[key] for row in rows if row[key] is not None]
        if not values:
            continue

        print(
            f"{key:16} "
            f"avg={sum(values) / len(values):9.2f} "
            f"min={min(values):9.2f} "
            f"max={max(values):9.2f} "
            f"stdev={pstdev(values):9.2f}"
        )


def format_optional(value, width):
    if value is None:
        return "n/a".rjust(width)

    return f"{value:{width}.2f}"


def format_ms(value, width):
    if value is None:
        return "n/a".rjust(width)

    return f"{value:{width}.0f}"


def latest_jfr(summary_dir):
    files = sorted(summary_dir.glob("*/spi-load-test.jfr"))
    return files[-1] if files else None


def run_jfr_view(view, jfr_file, width=180, max_lines=35):
    command = ["jfr", "view", "--width", str(width), view, str(jfr_file)]
    result = subprocess.run(command, check=False, text=True, capture_output=True)
    output = result.stdout.strip() or result.stderr.strip()
    lines = output.splitlines()
    return "\n".join(lines[:max_lines])


def print_jfr_details(summary_dir):
    jfr_file = latest_jfr(summary_dir)
    if not jfr_file:
        print("\nJFR DETAILS\nNo spi-load-test.jfr file found.")
        return

    if not shutil.which("jfr"):
        print("\nJFR DETAILS\nThe 'jfr' command is not available locally.")
        return

    print(f"\nJFR DETAILS\nfile: {jfr_file}")
    for view in [
        "container-cpu-throttling",
        "gc-pause-phases",
        "hot-methods",
        "allocation-by-class",
    ]:
        print(f"\n{view}")
        print(run_jfr_view(view, jfr_file))


def main():
    parser = argparse.ArgumentParser(
        description="Summarize k6/Kafka/CPU/JFR artifacts from load-test summary runs."
    )
    parser.add_argument("summary_dir", type=Path, help="Path like load-test/summary/10-jfr-baseline")
    parser.add_argument(
        "--active-seconds",
        type=float,
        default=60,
        help="Active k6 load duration used to calculate tx/s from transactions_started (default: 60).",
    )
    parser.add_argument(
        "--jfr-details",
        action="store_true",
        help="Print quick JFR views for the latest spi-load-test.jfr file.",
    )
    args = parser.parse_args()

    if not args.summary_dir.exists():
        raise SystemExit(f"Summary directory not found: {args.summary_dir}")

    if args.active_seconds <= 0:
        raise SystemExit("--active-seconds must be greater than zero")

    rows = collect_rows(args.summary_dir, args.active_seconds)
    if not rows:
        raise SystemExit(f"No run folders with summary.json found in: {args.summary_dir}")

    print_table(rows)
    print_aggregates(rows)

    if args.jfr_details:
        print_jfr_details(args.summary_dir)


if __name__ == "__main__":
    main()
