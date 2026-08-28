# Reference demo

This directory contains a small interactive demonstration of the instant-payment system. It is not part of the benchmarked core and carries no performance, durability, availability, or production-readiness guarantees.

The demo contains a minimal DICT, two simulated PSPs and an Angular application. Its only commitment is a reproducible happy path: create two customers, register a PIX key, resolve the receiver, submit a payment through the core and observe the resulting balance changes at both PSPs.

## Start

Start the core first from the repository root:

```bash
LOCAL_UID=$(id -u) LOCAL_GID=$(id -g) docker compose -f infra/docker-compose.yml up -d --build
```

Then start the reference demo:

```bash
LOCAL_UID=$(id -u) LOCAL_GID=$(id -g) docker compose -f demo/docker-compose.yml up -d --build
```

Open <http://localhost:4200>. PSP A is available at `http://localhost:8081`; PSP B is available at `http://localhost:8082`. The environment prepares Alice at PSP A and Bob at PSP B. Both can send and receive payments. Only Bob's `bob@example.com` key is pre-provisioned so the first payment works immediately; Alice starts without a key and the user may register one through the interface before sending money back from Bob. Choose either customer from the opening screen. The application briefly waits for the final outcome and keeps the latest payment statuses on both account screens. The [Bruno collection](../docs/collection/README.md) provides the same flow as explicit API calls.

The automated smoke exercises the complete business path and verifies the final balance changes and `SETTLED` payment histories observed by both PSPs:

```bash
./demo/smoke.sh
```

## Interface

![Reference demo account screen](screenshots/payment-app.png)

## Reset

DICT and PSP state are intentionally ephemeral, while the notification cursor refers to the durable core notification log. A clean demonstration therefore starts with both stacks empty; restarting only the demo against an existing core history is not supported.

```bash
docker compose -f demo/docker-compose.yml down --remove-orphans
docker compose -f infra/docker-compose.yml down -v --remove-orphans
LOCAL_UID=$(id -u) LOCAL_GID=$(id -g) docker compose -f infra/docker-compose.yml up -d --build
LOCAL_UID=$(id -u) LOCAL_GID=$(id -g) docker compose -f demo/docker-compose.yml up -d --build
```

## Scope

The demo deliberately does not provide production-grade PSP persistence, HA, performance tuning, comprehensive error handling, broad test coverage, or a complete DICT. The Rust load tool remains the authoritative instrument for core correctness and performance validation.
