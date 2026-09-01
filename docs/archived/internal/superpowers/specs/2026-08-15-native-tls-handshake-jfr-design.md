# Native TLS handshake JFR diagnostic

## Purpose

Determine whether repeated TLS handshakes explain the CPU saturation observed
at the `kafka-producer` HTTPS/mTLS ingress. This is an evidence-gathering change,
not a performance optimization.

The first diagnostic run showed the `kafka-producer` near one full CPU and JFR
execution samples dominated by X25519 key-agreement methods. The missing fact is
the number and timing of completed TLS handshakes. Until that is known, changing
connection pooling or instrumenting the Kafka callback would be premature.

## Scope

Enable the JDK-native `jdk.TLSHandshake` event only in the JFR recording of the
`kafka-producer`.

Do not add application events, Java instrumentation, logs, endpoints, profiles,
report fields, or workload behavior. In particular, do not add a custom
`KafkaPublish` event in this slice.

## Recording configuration

The existing runner starts each container recording with `settings=profile`.
Extend the internal JFR script interface so `start` accepts optional JFR event
settings after its fixed arguments and forwards them unchanged to `JFR.start`.

The `kafka-producer` recording receives:

```text
jdk.TLSHandshake#enabled=true
```

The SPI and notification-gateway recordings receive no override and continue
to use the unmodified `profile` settings.

The override applies only to the active recording. It must not edit or copy the
`profile.jfc` installed on the host or in a container. The existing output
contract remains `diagnostics/jfr/kafka-producer.jfr`; no additional artifact is
introduced.

## Failure semantics

An unsupported or malformed event setting makes `JFR.start` fail before load
generation. The runner preserves its existing diagnostic-start failure
behavior; it must not silently continue with a recording that omitted the
requested event.

The native event describes completed handshakes. If a smoke with successful
mTLS traffic still records zero `jdk.TLSHandshake` events, the experiment is
inconclusive. It must not be interpreted as evidence of connection reuse. That
case requires investigating JDK/Netty event compatibility before any A/B.

## Experiment and interpretation

First run an instrumented functional smoke and verify that the JFR contains at
least one `jdk.TLSHandshake` event. Then repeat
`mixed-outcomes-2k-diagnostic` with the existing `--jfr` diagnostics.

Post-processing compares, over the same run timeline:

- completed TLS handshakes;
- all HTTP attempts initiated by the load-tool, including originals, status
  messages, and replays;
- `kafka-producer` CPU;
- HTTP timeouts.

The comparison is diagnostic rather than a new report contract:

- handshakes that continue throughout active load, materially outgrow the
  initial connection establishment, and track CPU/timeouts support connection
  churn as the next A/B target;
- handshakes concentrated during setup or warmup, followed by substantial
  connection reuse while CPU/timeouts remain high, weaken the churn hypothesis
  and justify instrumenting the Kafka publish callback next;
- absent or temporally ambiguous evidence authorizes more instrumentation, not
  a connection-pool change.

No fixed handshake/request threshold is introduced in this slice.

## Tests and verification

- Extend the JFR shell test to assert that only the `kafka-producer` start
  command includes `jdk.TLSHandshake#enabled=true`.
- Preserve the existing start, stop, copy, cleanup, and failure behavior for all
  three recordings.
- Run `bash -n` on the changed scripts and all existing load-test shell tests.
- Run a real mTLS smoke with `--jfr` and verify a nonzero native event count
  before starting the short 2k diagnostic.
- Keep application code, workload profiles, SLA report, and resource limits
  unchanged.
