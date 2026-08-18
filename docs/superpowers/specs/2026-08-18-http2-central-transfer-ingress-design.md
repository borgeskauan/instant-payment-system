# HTTP/2-only central-transfer ingress

## Purpose

Replace the load-tool-to-`kafka-producer` HTTP/1.1 connection pool with
mandatory HTTP/2 over mTLS, and establish every PSP session before load
generation begins.

The `mixed-outcomes-2k-diagnostic` run with the keyed, eight-consumer SPI
variant preserved low participant-balance locking but collapsed at ingress:
the `kafka-producer` averaged approximately one CPU, most execution samples
landed on its single Reactor Netty event-loop thread, TLS handshakes increased,
and only 693 of 6,410 active original attempts received HTTP 2xx. The design
must remove repeated connection establishment from the measured path without
reducing the workload, weakening SPI concurrency or adding CPU.

HTTP/2 is an architectural requirement for this server-to-server boundary. It
is not an optional optimization and has no HTTP/1.1 fallback.

## Protocol contract

The `kafka-producer` listener supports only HTTP/2 over TLS:

- configure Reactor Netty with `HttpProtocol.H2`, without `HTTP11` or H2C;
- configure its server TLS context for HTTP/2 ALPN;
- continue requiring and validating the PSP client certificate;
- do not advertise or accept HTTP/1.1;
- retain the existing `/transfer` and `/transfer/status` payload,
  authentication, authorization and response contracts.

The load-tool uses the Go 1.24 standard-library transport with a non-nil
`http.Protocols` that enables HTTP/2 and leaves HTTP/1 disabled. Merely setting
`ForceAttemptHTTP2` is insufficient because it permits negotiation of either
protocol.

Every received response must be HTTP/2. Failure to negotiate `h2` is a
connection/protocol error, never a reason to downgrade.

## Persistent PSP clients

Keep one independently configured `http.Client` and transport per PSP
certificate identity. Construct each client once and reuse it concurrently for
the complete run. The standard transport owns its connection pool and HTTP/2
stream multiplexing; do not implement a custom connection pool or require
exactly one physical connection.

Retain the existing per-PSP connection ceiling as a defensive resource bound,
but rename its HTTP/1.1-specific representation. The ceiling is not a profile,
CLI or environment setting. Retain the existing request timeout and idle
timeout so protocol plus prewarming remain the only experiment intervention.

If a physical connection fails, the transport may establish another HTTP/2
connection for the same PSP. It may not retry a payment at the application
level or fall back to HTTP/1.1. Only the workload's already configured replay
obligations may submit an additional PACS message.

## Health endpoint and prewarming

Add a lightweight `GET /health` route to the existing Reactor Netty server. Do
not add Spring Boot or Actuator: `kafka-producer` is a standalone Reactor Netty
application, and an application framework would add unrelated runtime and
cognitive cost.

The endpoint:

- runs on the same HTTP/2-only mTLS listener as the transfer routes;
- applies the same certificate-identity extraction used by transfer requests;
- returns HTTP 200 for an authenticated PSP;
- returns the existing authentication status on identity failure;
- never calls `PaymentPublisher` and never publishes to Kafka.

The application already warms the Kafka publisher before binding the HTTP
server. Therefore a successful health request proves that the listener was
started after the publisher's startup warmup; it is not a promise of continuous
Kafka health after startup.

Before calculating or recording the generation window, the simulator sends
one health request through every PSP client it will use. Preparation succeeds
only when all requests return HTTP 2xx over HTTP/2. The same client and
transport instances then serve warmup, active traffic, drain and configured
replays.

Health requests may execute concurrently because they precede the measured
workload. Their concurrency is internal and not configurable. Any health,
mTLS, identity or protocol failure aborts the run before payment generation;
it must not create PACS.008/PACS.002 event rows or business-side effects.

## Runtime semantics

Transfer behavior does not change:

- `/transfer` and `/transfer/status` return HTTP 2xx only after the Kafka send
  completes successfully;
- Kafka publication failure remains an HTTP failure;
- request timeout and connection failure remain observed HTTP-attempt failures;
- no early response, hidden retry or admission buffer is introduced;
- PACS.008/PACS.002 payloads, Kafka keys, SPI concurrency, outcomes and replay
  selection remain unchanged.

Do not add negotiated-protocol data to event CSVs or `sla-report.json`. Strict
client/server configuration makes HTTP/1.1 an error rather than a reportable
workload dimension. Emit one preparation summary in the load-tool log:

```text
central transfer HTTP/2 prewarm finished: psps=<count> protocol=h2
```

## Automated verification

The `kafka-producer` suite must prove:

- a valid mTLS HTTP/2 client can call `/health`, `/transfer` and
  `/transfer/status`;
- an HTTP/1.1-only TLS client cannot negotiate or complete a request;
- `/health` validates PSP identity and never invokes `PaymentPublisher`;
- both transfer routes still wait for publisher completion before returning
  HTTP 2xx;
- existing transfer authentication, authorization and publisher-failure
  behavior is preserved.

The Go suite must prove:

- the central-transfer transport enables HTTP/2 and not HTTP/1;
- every planned PSP is prewarmed before generation begins;
- a health non-2xx, mTLS failure or non-HTTP/2 response aborts before any
  generated event or request;
- the client instance used for health is retained for subsequent traffic and
  can reuse the prewarmed session;
- distinct PSP identities retain distinct certificate-bearing clients;
- concurrent HTTP/2 requests remain supported without requiring an exact
  physical-connection count;
- no application retry is introduced.

Run the complete `kafka-producer`, Go load-tool and shell suites, syntax checks,
Compose validation and `git diff --check` before a real run.

## Controlled diagnostic

After automated verification and a qualified functional smoke, run one
`mixed-outcomes-2k-diagnostic` experiment. Compare it with the preserved
`reservation-balance-kafka-concurrency-eight-diagnostic/20260818_001442`
control.

Keep fixed:

- Kafka ISPB keying and eight SPI consumers, one per partition;
- one vCPU for `kafka-producer`;
- one Reactor Netty I/O worker;
- profile, participant distribution, durations, replay shares and delays;
- mTLS identities, five-second request timeout and all other resource limits;
- diagnostic collection used by the control.

Verify first that every planned PSP was prewarmed over HTTP/2 and that no
HTTP/1.1 request was accepted. Then compare:

- active original attempts, requests written and HTTP 2xx responses;
- connection-acquisition and pre-write timeouts;
- `kafka-producer` CPU and event-loop concentration;
- completed TLS handshakes by phase, especially during the active window;
- PACS.002 admission, replay outcomes and end-to-end notifications;
- ACSC and RJCT/AM04 correctness;
- rolling throughput, latency and drain;
- SPI participant-balance lock evidence, to ensure the keyed/c8 benefit is
  preserved.

The diagnostic neither changes resources nor tunes another subsystem. Because
HTTP/2-only is the required boundary contract, a result below 2,000 TPS does
not authorize fallback to HTTP/1.1. It identifies the next measured bottleneck.
A correctness, authentication or protocol failure blocks the candidate; a
performance shortfall keeps the stabilization task open.

## Out of scope

- HTTP/1.1 compatibility or downgrade;
- H2C, HTTP/3, Spring Boot Actuator or a separate management listener;
- custom stream scheduling or a custom connection-pool implementation;
- HTTP/2-specific public profile knobs;
- CPU, Reactor worker, timeout, Kafka, SPI or workload tuning in the same
  experiment;
- a 15-minute qualification run or final SLA approval.
