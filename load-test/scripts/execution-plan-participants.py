#!/usr/bin/env python3

import json
import sys


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(f"Usage: {sys.argv[0]} EXECUTION_PLAN")

    path = sys.argv[1]
    try:
        with open(path, encoding="utf-8") as handle:
            document = json.load(handle)
        for scenario in document["scenarios"]:
            participants = scenario["participants"]
            provisioning = scenario["provisioning"]
            values = (
                participants["pairNumberStart"],
                participants["hotPairCount"],
                participants["coldPairCount"],
                provisioning["payerBalance"],
                provisioning["receiverBalance"],
                str(provisioning["resetIfExists"]).lower(),
            )
            print("\t".join(str(value) for value in values))
    except (OSError, json.JSONDecodeError, KeyError, TypeError) as error:
        raise SystemExit(f"Cannot read normalized execution plan {path!r}: {error}") from error


if __name__ == "__main__":
    main()
