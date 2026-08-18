# Kafka ISPB Key A/B Design

**Date:** 2026-08-17

## Purpose

Test whether the participant-row contention observed by the reservation-balance
experiment is primarily caused by records for the same participant being spread
across Kafka partitions and processed concurrently by different consumers.

This experiment keeps the one-row participant balance architecture. It changes
only the Kafka record key used by the ingress producer.

## Hypothesis

Kafka currently receives null-keyed payment and status records. The sticky
partitioner may therefore place records for the same ISPB in different
partitions, allowing concurrent consumer transactions to contend for the same
`participant_balance_entity` row.

Using the authenticated ISPB as the Kafka record key gives all records for that
ISPB, within one topic, stable partition affinity. One consumer in the group
owns that partition at a time, so same-topic mutations for the participant are
processed sequentially. Existing consumer-side grouping can still aggregate
the participant's records within each poll.

The key does not create one partition or one pure batch per ISPB. Multiple
ISPBs may share a partition. It also does not serialize work across the
PACS.008 and PACS.002 topics, which have independent consumer groups.

## Record keys

- PACS.008 / `spi-payment-requests`: key with the authenticated payer ISPB. The
  existing authorization rule already requires it to equal the message sender
  ISPB.
- PACS.002 / `spi-payment-status-reports`: key with the authenticated reporting
  PSP ISPB. For the accepted statuses exercised by the current mixed workload,
  this is the receiver whose balance is credited.

The current internal PACS.002 payload contains only payment ID, status, and
reasons. For a receiver-originated rejection, the producer cannot derive the
payer whose balance will be released. That case remains protected by
PostgreSQL locking but is not claimed to receive payer affinity from this
change.

Keys use the UTF-8 bytes of the authenticated ISPB. Payloads and authentication
headers remain byte-for-byte unchanged.

## Scope controls

The experiment does not change:

- topic count, topic names, or eight-partition topology;
- consumer groups, listener concurrency `3`, poll sizing, or acknowledgements;
- SPI transaction boundaries, SQL, balance semantics, audit, or outbox;
- PostgreSQL and container resources;
- HTTP behavior, workload profiles, replay settings, diagnostics, or deadlines;
- the configured mixed-outcomes traffic distribution.

Application-managed participant lanes, cross-poll microbatching, new topics,
payload enrichment, and cross-topic serialization remain outside this
experiment.

## Correctness and tests

Focused producer tests must prove that:

- every emitted PACS.008 record uses the authenticated payer ISPB as its key;
- every emitted PACS.002 record uses the authenticated reporting PSP ISPB as
  its key;
- multiple records published for one authenticated ISPB receive identical
  keys;
- different authenticated ISPBs receive their own keys;
- payloads and `authenticated-ispb` headers remain unchanged;
- publication failures retain the existing error behavior.

Existing kafka-producer, SPI, shell, and Go test suites remain green. A
qualifying functional smoke must retain the exact mixed-outcomes semantics,
including replay idempotency.

## Performance experiment

The immutable unkeyed single-balance run is:

`load-test/results/reservation-balance-diagnostic/20260816_221950`

After rebuilding a clean environment with the keyed producer, execute exactly
one `mixed-outcomes-2k-diagnostic` run. Do not execute the 15-minute profile.
Capture the same phase-aligned evidence as the unkeyed run:

- original request starts, 2xx responses, timeouts, and rolling floor;
- active and total accepted happy-path PACS.002;
- matched, missing, and contradictory outcomes;
- replay violations;
- PostgreSQL CPU and Kafka lag;
- `participant_balance_entity` wait count, total time, and maximum wait;
- normalized financial SQL cost per accepted PACS.002.

The result is:

- **KEEP for further evaluation** when correctness remains intact, active
  accepted PACS.002 does not fall below `28,393`, the rolling floor does not
  fall below `459/s`, and both the count and maximum duration of native
  participant-balance waits above one second improve from the unkeyed control
  (`10` waits, `28.904898 s` maximum);
- **DISCARD** for any functional or replay violation, useful-work regression,
  rolling-floor regression, or equal/worse participant-balance contention;
- **INCONCLUSIVE** when useful-work and lock evidence move in opposite
  directions or the bundle is not comparable.

`KEEP` does not authorize a merge or the 15-minute profile. It only establishes
that Kafka participant affinity is worth another stabilization step. The
original performance requirement of sustained 2,000 TPS remains unapproved.
