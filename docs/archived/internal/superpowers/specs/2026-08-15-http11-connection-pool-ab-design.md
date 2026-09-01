# HTTP/1.1 connection-pool A/B

## Purpose

Test whether bounding and reusing HTTP/1.1 connections per PSP breaks the
observed feedback loop between request timeouts, closed connections, mTLS
handshakes and CPU saturation at the `kafka-producer` ingress.

The baseline `tls-handshake-diagnostic/20260815_143609` produced 14,226 HTTP
attempts and 6,717 completed TLS handshakes. Handshakes recurred during active
load and drain, while the ingress remained at approximately one CPU and most
requests ended without an HTTP status. This supports a controlled intervention,
but does not yet prove that connection churn is the primary causal bottleneck.

## Fixed intervention

Keep HTTP/1.1, mTLS, the five-second client timeout and the 90-second idle
timeout. Keep one independent client and certificate identity per PSP.

Replace the effectively unbounded per-PSP transport limits with one internal
constant:

```text
maxHTTP11ConnectionsPerPSP = 32
```

Use that value for `MaxConnsPerHost`, `MaxIdleConnsPerHost` and
`MaxIdleConns`. Since each PSP owns a transport that talks to one ingress host,
this represents a fixed pool of at most 32 active or idle connections for that
PSP.

Do not add a profile field, CLI flag or environment variable. Do not change the
server, resource limits, HTTP protocol, TLS configuration, session resumption,
Kafka configuration, workload or timeout in the same experiment.

Thirty-two is deliberately a middle intervention. Each hot PSP receives about
160 original payments per second in the diagnostic workload. A pool of 32 can
carry that rate while ingress acknowledgements remain below approximately 200
milliseconds, while still becoming bounded during the existing five-second
collapse. Sixteen connections risk turning the client into the primary limit;
48 may not materially constrain the observed connection storm.

## Transport observations

The existing `request_started_at_ns` is the logical start of the HTTP attempt,
before `client.Do`. It cannot distinguish work waiting inside `net/http` from a
request that reached a connection.

Attach a standard-library `httptrace.ClientTrace` to every POST and record:

- `connection_acquired_at_ns` from `GotConn`;
- `request_written_at_ns` from a successful `WroteRequest` callback;
- `connection_reused` from `GotConnInfo.Reused`.

A zero acquisition timestamp means no connection was obtained. A zero written
timestamp means the request was not completely written. `connection_reused`
is meaningful only when acquisition is nonzero.

`GotConnInfo.Reused` describes the connection ultimately delivered to that
request; it is not a count of TLS dials. Go's transport may let a dial already
in progress complete and enter the idle pool after another connection satisfies
the waiting request. Such late-bound connections produce server-side handshake
events without a corresponding final `GotConn` value of false. Use JFR as the
authoritative aggregate handshake count, and do not derive new connections as
`acquired - reused`.

Append these fields to each existing HTTP-attempt artifact:

- `events/pacs008-starts.csv`;
- `events/pacs002-starts.csv`;
- `events/replays.csv`.

Do not create another artifact or add transport details to `sla-report.json`.
The event readers must consume the new exact headers; compatibility with the
superseded CSV layouts is not required because historical report reruns are not
supported.

## Experiment

The A result is the preserved baseline
`tls-handshake-diagnostic/20260815_143609`. The B result uses the same
`mixed-outcomes-2k-diagnostic` profile, revision baseline, resources and
diagnostic options, with only the fixed pool and transport observations added.

Run a functional smoke first. Then run B with `--jfr --spi-trace
--postgres-statements` and compare:

- original payments logically started and actually written by phase;
- acquisition wait and requests that never acquire or write;
- connection acquisition/reuse reported to requests and authoritative JFR
  handshake counts;
- completed TLS handshakes by phase;
- ingress CPU and HTTP outcomes;
- Kafka admission as indirectly observable through external outcomes;
- end-to-end latency, rolling throughput and drain.

Queueing inside the pool is not automatically a failure. It is healthy
backpressure if requests continue to be written and completed at the required
rate, latency remains inside SLA and wait does not grow progressively. The B
result is confounded if handshake reduction is explained only by a growing
client backlog and lower network admission.

## Decision

Keep the bounded pool as a candidate when it materially increases reuse,
reduces recurring handshakes and ingress CPU, and improves actual written or
completed throughput without a growing client-side backlog or functional
regression.

Discard or revise the value when it merely moves the bottleneck into the
load-tool, reduces network admission without compensating throughput gains, or
does not materially change CPU/handshakes.

This experiment does not authorize HTTP/2. A later experiment may retain a
small per-PSP pool while replacing connection concurrency with multiplexed
HTTP/2 streams.

## Verification

- Unit-test the fixed transport limits and keep-alive behavior.
- Unit-test acquisition, write and reuse observations with two sequential
  requests over one real HTTP/1.1 test connection.
- Round-trip all three extended CSV contracts.
- Keep existing report behavior unchanged.
- Run all Go and load-test shell tests, syntax checks and `git diff --check`.
- Run a real mTLS smoke before the short B diagnostic.
