# SPI Kafka failure and DLQ cleanup

## Objective

Make failure handling consistent across the SPI `pacs.008` and `pacs.002` Kafka consumers without introducing a generic error-handling framework.

The cleanup must keep four meanings separate:

| Classification | Meaning | Destination |
| --- | --- | --- |
| business outcome | valid input produced an expected result | normal audit and notification flow |
| permanent record failure | retrying the same record cannot make it valid | publish only that record to DLQ and continue the batch |
| transient infrastructure failure | processing may succeed after a dependency recovers | do not ACK and do not use DLQ; retry without a finite recovery limit |
| internal defect | a bug or violated invariant cannot be attributed safely to one record | roll back, retry the batch a bounded number of times, then publish the batch to DLQ |

The source batch is acknowledged only after valid work finishes and every record-local DLQ publication succeeds.

## Ownership boundaries

- The Kafka Producer authenticates the PSP through mTLS, translates the external PACS contract and performs only the cheap structural work needed to create the internal message.
- The SPI is the single authority for the internal payment contract, payer/receiver authorization and domain invariants. Equivalent validation must not be duplicated in the producer and SPI.
- The minimum internal `PaymentRequest` contract requires a nonblank compatible payment identity, a positive supported amount and currency, valid payer and receiver ISPBs, and a supported internal account type when present.
- Once payment state, audit and the notification outbox commit atomically, the source record's recovery responsibility has ended. Failure of the in-memory publication fast path belongs to outbox recovery and must not fail the original Kafka record.
- Spring Kafka continues to own `poll`, rebalance, offset commit and redelivery behavior. Existing Kafka infrastructure handling requires no redesign.

## Final failure matrix

| Case | Classification and behavior |
| --- | --- |
| missing or invalid authenticated ISPB | permanent record failure; `NOT_AUTHENTICATED` DLQ |
| malformed protobuf or known input-derived semantic error | permanent record failure; `INVALID_PAYLOAD` DLQ |
| authenticated PSP is not authorized as payer or receiver | permanent record failure; `UNAUTHORIZED_PSP` DLQ |
| divergent `pacs.008` identity | permanent record failure; `DIVERGENT_DUPLICATE` DLQ |
| unknown payment, conflicting intrabatch status or contradiction with terminal state | permanent record failure; `STATUS_REPORT_CONFLICT` DLQ |
| identical `pacs.008` or terminal `pacs.002` replay | expected idempotent no-op; no DLQ |
| insufficient funds or valid receiver rejection | expected business outcome; normal audit and notification flow |
| missing participant balance row | provisioning/integrity defect; roll back, bounded batch retry, then batch DLQ |
| database reachability, transient lock or transient query failure | transient infrastructure failure; no ACK and no DLQ |
| unexpected mapper, serialization, persistence, arithmetic or invariant failure | internal defect; roll back, bounded batch retry, then batch DLQ |
| database integrity violation after authoritative ingress validation | internal defect; do not inspect constraint names to infer input semantics |
| failure after the durable outbox transaction committed | acknowledge the source record; outbox recovery owns publication |
| record-level DLQ publication failure | do not ACK; preserve existing redelivery behavior |

Record-level DLQ publication is at-least-once. Duplicate DLQ records are accepted in favor of avoiding loss. This slice adds no deduplication, exactly-once transaction or metadata.

## Authorized changes

1. Add the minimum authoritative `PaymentRequest` validation at the SPI boundary.
2. Make known `pacs.008` semantic input failures record-local, matching the existing `pacs.002` behavior.
3. Treat missing participant balances consistently as provisioning/integrity defects in both flows.
4. Route genuinely transient Spring data-access failures through the existing unlimited infrastructure retry path.
5. Stop propagating failure of the post-commit in-memory outbox handoff to the source consumer.
6. Rename `DIVERGENT_STATUS_REPORT` to `STATUS_REPORT_CONFLICT` without splitting the DLQ taxonomy.
7. Consolidate duplicate authorization checks at the SPI domain boundary. Removal of the Kafka Producer's synchronous payer authorization remains part of that project's cleanup and must explicitly document the resulting removal of the current HTTP `403` behavior.

## Constraints

Do not add a generic failure classifier, handler registry, DLQ replay feature, exactly-once DLQ mechanism, new metadata, constraint-name parsing, lazy participant provisioning, Kafka recovery mechanism or new business rejection.

New exception types are justified only when an existing type cannot express one of the final classifications clearly.

## Validation

Tests must protect behavior rather than implementation structure:

- a permanent record failure reaches DLQ without blocking valid records from the same batch;
- an expected business outcome never reaches DLQ;
- a transient database failure is not acknowledged or parked in DLQ;
- an unisolatable internal defect rolls back and follows batch recovery;
- a missing participant balance is not reported as insufficient funds;
- a failed record-level DLQ publication cannot reach the source ACK;
- a committed durable outbox obligation is not converted into source failure by an in-memory handoff error;
- `pacs.008` and `pacs.002` apply equivalent classifications.

## Out of scope

- generic error-handling abstractions;
- DLQ replay or deduplication;
- Kafka infrastructure redesign;
- retry timing or resource tuning;
- external PACS contract changes;
- extraction of business policies from the large JDBC adapters;
- multi-instance, HA or disaster-recovery validation.

## Completion

The slice is complete when the authorized changes are implemented, focused SPI tests pass and the active cleanup task references this failure contract as the authoritative classification.
