# SPI Safe Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove residual SPI infrastructure that does not contribute to payment processing while preserving sampled semantic performance checkpoints through JFR.

**Architecture:** Replace the bespoke CSV trace lifecycle with a deterministic 1% sampled JFR stage event, then remove dead runtime configuration, unused dependencies and test-only APIs. Consolidate the duplicated DLQ publishers behind one publisher and one exception-to-error-type mapping without changing topics, headers, failure propagation or acknowledgment ordering.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Kafka, JDK Flight Recorder, Maven, Bash.

**Spec:** The agreed boundary in the current conversation: keep authenticated/idempotent financial processing, minimal audit and transactional notification delivery; defer schema, status-model, reason-semantics and persistence-boundary decisions.

## Global Constraints

- Preserve all financial transitions, locking, idempotency, audit and notification behavior.
- Preserve the six existing sampled semantic checkpoints and their `endToEndId` correlation.
- JFR is the sole lifecycle and storage mechanism for SPI semantic performance checkpoints.
- Preserve every current DLQ error type, topic, partition, header and fail-closed behavior.
- Do not change migrations, persisted status types, incoming reason semantics or JDBC transaction algorithms.
- Do not commit automatically; leave the completed diff available for review.

---

### Task 1: Replace the CSV trace with sampled JFR stages

**Files:**
- Create: `spi/src/main/java/br/kauan/spi/domain/services/tracing/SpiPaymentStageEvent.java`
- Create: `spi/src/main/java/br/kauan/spi/domain/services/tracing/SpiPaymentStage.java`
- Modify: `spi/src/main/java/br/kauan/spi/adapter/input/kafka/consumer/InboundPaymentMessageDecoder.java`
- Modify: `spi/src/main/java/br/kauan/spi/domain/services/PaymentTransactionProcessorService.java`
- Test: `spi/src/test/java/br/kauan/spi/domain/services/tracing/SpiPaymentStageEventTest.java`

**Interfaces:**
- Consumes: payment identifier and one of the six existing semantic stages.
- Produces: `SpiPaymentStageEvent.record(String, SpiPaymentStage)`, emitting a JFR event only for the deterministic 1% sample.

- [ ] **Step 1: Write a failing JFR behavior test**

  Record JFR events for a hand-selected sampled identifier and a non-sampled identifier. Assert that the sampled identifier emits the requested stages with the identifier and stage fields, while the non-sampled identifier emits none.

- [ ] **Step 2: Run the focused test and verify RED**

  Run `./mvnw -Dtest=SpiPaymentStageEventTest test` from `spi/` and confirm compilation fails because `SpiPaymentStageEvent` does not exist.

- [ ] **Step 3: Implement the minimal JFR event**

  Add one instant JFR event with `endToEndId` and `stage` fields. Keep deterministic `floorMod(endToEndId.hashCode(), 100) == 0` sampling. Do not add a queue, writer, lifecycle, endpoint or runtime tuning property.

- [ ] **Step 4: Route the six existing checkpoints through JFR**

  Replace injected `SpiTraceRecorder` calls in the decoder and processor with `SpiPaymentStageEvent.record(...)`. Preserve the exact points at which each existing stage is emitted.

- [ ] **Step 5: Run the focused test and affected service tests**

  Run the JFR test, `PaymentMessageConsumerTest` and `PaymentTransactionProcessorServiceTest`; confirm all pass after adapting constructors and removing mock-only trace assertions.

### Task 2: Remove the obsolete trace lifecycle from SPI and load-test orchestration

**Files:**
- Delete: `spi/src/main/java/br/kauan/spi/domain/services/tracing/SpiTraceRecorder.java`
- Delete: `spi/src/main/java/br/kauan/spi/adapter/input/admin/SpiTraceAdminController.java`
- Delete: corresponding recorder/controller tests
- Delete: `load-test/scripts/spi-trace.sh`
- Modify: `spi/src/main/resources/application.yml`
- Modify: `infra/docker-compose.yml`
- Modify: `load-test/run-load-test.sh`
- Modify: `load-test/scripts/run-diagnostics.sh`
- Modify: affected shell tests and current documentation

**Interfaces:**
- Consumes: the existing JFR diagnostic switch.
- Produces: one diagnostic lifecycle, with no `--no-spi-trace` option or `spi-trace.csv` artifact.

- [ ] **Step 1: Update shell tests to describe the reduced diagnostic contract**

  Remove SPI trace expectations from the diagnostics layout/default tests while retaining JFR and PostgreSQL diagnostic behavior.

- [ ] **Step 2: Run affected shell tests and verify RED**

  Confirm they fail because the runner still exposes and collects the old trace.

- [ ] **Step 3: Remove the CSV trace lifecycle**

  Delete recorder/controller/script and remove properties, Compose environment, runner flag propagation, trace artifact collection and obsolete documentation references.

- [ ] **Step 4: Run the shell tests and SPI tests**

  Confirm the final diagnostic bundle still contains JFR and PostgreSQL evidence and the SPI context no longer requires trace beans.

### Task 3: Remove provably dead infrastructure and APIs

**Files:**
- Delete: `spi/src/main/java/br/kauan/spi/adapter/input/SchedulerConfig.java`
- Delete: `spi/src/main/java/br/kauan/spi/adapter/input/NettyConfig.java`
- Modify: `spi/pom.xml`
- Modify: `spi/src/main/resources/application.yml`
- Modify: `spi/src/main/java/br/kauan/spi/domain/services/notification/NotificationObligationService.java`
- Modify: `spi/src/main/java/br/kauan/spi/domain/services/notification/payload/NotificationPayloadFactory.java`
- Modify: affected notification tests

**Interfaces:**
- Consumes: only currently called notification APIs.
- Produces: the same active notification payloads and obligations without unused entry points or dependencies.

- [ ] **Step 1: Characterize the active notification API**

  Ensure tests cover acceptance and rejection obligations through `storeTransactionObligations` and status obligations through the active status path before deleting test-only overloads.

- [ ] **Step 2: Run the focused notification tests**

  Establish GREEN for the active API before structural deletion.

- [ ] **Step 3: Delete dead infrastructure and APIs**

  Remove the unused scheduler, unmeasured Netty customization, MapStruct configuration/dependency, springdoc WebMVC dependency, unused initial-balance property, `storeAcceptanceObligations`, generated-message-id payload overloads and dead mapper direction. Keep JPA until the later persistence decision.

- [ ] **Step 4: Run focused and compile verification**

  Run notification tests and `./mvnw -DskipTests compile` from `spi/`.

### Task 4: Consolidate DLQ publishing

**Files:**
- Create: `spi/src/main/java/br/kauan/spi/adapter/input/kafka/infrastructure/dlq/DlqPublisher.java`
- Modify: `spi/src/main/java/br/kauan/spi/adapter/input/kafka/infrastructure/dlq/KafkaDlqConfig.java`
- Modify: `spi/src/main/java/br/kauan/spi/adapter/input/kafka/consumer/PaymentMessageConsumer.java`
- Delete: five specialized `*DlqPublisher.java` wrappers
- Test: `KafkaDlqConfigTest.java`, `PaymentMessageConsumerTest.java`

**Interfaces:**
- Consumes: `publish(ConsumerRecord<String, byte[]>, Exception)`.
- Produces: the existing DLQ record with error type derived from the exception class and identical fail-closed behavior.

- [ ] **Step 1: Make the single-recoverer classification test fail**

  Update `KafkaDlqConfigTest` so the same recoverer is exercised with invalid payload, divergent duplicate, divergent status, missing authentication, unauthorized PSP and generic batch failure exceptions. Assert the existing literal `dlq.error-type` values.

- [ ] **Step 2: Run the focused test and verify RED**

  Confirm specialized exceptions currently receive `BATCH_PROCESSING_ERROR` from the single recoverer.

- [ ] **Step 3: Implement one exception classifier and publisher**

  Use one `DeadLetterPublishingRecoverer` whose headers function maps the existing exception classes to the existing error-type strings. Add one publisher wrapper and inject it once into the consumer.

- [ ] **Step 4: Adapt consumer behavior tests**

  Preserve per-record continuation, ordering before Kafka acknowledgment and propagation when DLQ publication fails.

- [ ] **Step 5: Run focused tests and remove wrappers**

  Delete the five specialized publishers only after the single publisher tests pass.

### Task 5: Verify the complete safe cleanup

**Files:**
- Modify only files required by failures caused by the cleanup.

**Interfaces:**
- Consumes: the complete repository.
- Produces: a reviewable, uncommitted cleanup diff.

- [ ] **Step 1: Run the SPI suite**

  Run `./mvnw test` from `spi/` and require a zero exit code.

- [ ] **Step 2: Run affected load-test shell tests**

  Run diagnostics layout/default, JFR diagnostics and observable outcome flow tests.

- [ ] **Step 3: Run static verification**

  Run `bash -n` on modified scripts, `git diff --check`, searches for removed trace/flag/dependencies and `git status --short`.

- [ ] **Step 4: Review scope**

  Confirm no migration, status enum, reason semantics, transaction SQL or runtime tuning value changed.
