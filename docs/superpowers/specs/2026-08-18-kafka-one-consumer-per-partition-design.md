# Kafka one-consumer-per-partition A/B design

## Objective

Test whether the end-to-end regression observed after keying SPI ingress by ISPB
was caused by assigning multiple unevenly loaded Kafka partitions to each child
consumer.

The experiment preserves the existing `mixed-outcomes-2k-diagnostic` workload,
including its 80/20 hot-pair distribution and ten hot pairs. It does not make
the workload easier to distribute.

## Single experimental change

Change `spi.kafka.listener-concurrency` from `3` to `8`. Both SPI listeners use
the same container factory, so each of the two independent consumer groups will
have up to eight child consumers for its eight-partition topic:

- PACS.008: one consumer per payment-request partition;
- PACS.002: one consumer per status-report partition.

Keep unchanged:

- the ISPB Kafka key and default Kafka partitioner;
- topic names and eight-partition topology;
- payloads, authenticated-ISPB header and consumer groups;
- poll size, batch processing, acknowledgements and assignment strategy;
- SQL, transaction boundaries and the single-balance architecture;
- SPI resources, including the one-vCPU limit;
- load-test profile, replay configuration and diagnostics.

This does not make partition load uniform. A partition containing several hot
ISPBs remains hotter than another partition. It only prevents one child
consumer from serially owning several hot partitions while another owns mostly
cold partitions.

## Verification and experiment

Protect the configured concurrency through an automated configuration test,
then run the existing SPI, Kafka producer, Go load-tool and shell suites.

Use the existing keyed/concurrency-3 bundle
`reservation-balance-kafka-key-diagnostic/20260817_234042` as the immutable
control. Recreate the stack without removing build caches, qualify it with the
existing functional smoke, and execute exactly one
`mixed-outcomes-2k-diagnostic` run with concurrency 8. Do not execute the
15-minute profile and do not rerun the diagnostic to improve its result.

Compare the runs over their authoritative half-open active windows. Record at
least:

- original PACS.008 starts, 2xx responses, timeouts and rolling floor;
- active and total PACS.002 starts, acceptances and timeouts;
- matched, missing and contradictory business outcomes;
- PACS.008 and PACS.002 replay violations;
- immediate and post-drain Kafka lag;
- PostgreSQL CPU and participant-balance lock evidence;
- partition and consumer-group distribution.

## Decision

The concurrency-8 variant is a candidate for further evaluation only if it:

- preserves functional correctness and produces no contradictory outcome;
- preserves the removal of material participant-balance contention;
- increases useful PACS.008/PACS.002 work and improves the rolling floor;
- reduces timeouts and replay violations relative to the keyed/concurrency-3
  control;
- achieves those improvements without changing workload or resource limits.

Discard the exact configuration if correctness regresses, participant-balance
contention materially returns, or useful work and temporal coverage fail to
improve. Classify the experiment as inconclusive if the qualified bundle is not
comparable or the main signals move in opposite directions.

A favorable short result authorizes neither a merge nor the 15-minute profile.
It only establishes that one consumer per partition is worth a later
stabilization decision.
