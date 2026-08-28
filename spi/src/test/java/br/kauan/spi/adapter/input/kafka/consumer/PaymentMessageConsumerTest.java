package br.kauan.spi.adapter.input.kafka.consumer;

import br.kauan.pix.internal.v1.BankAccount;
import br.kauan.pix.internal.v1.Party;
import br.kauan.pix.internal.v1.PaymentRequest;
import br.kauan.pix.internal.v1.PaymentStatus;
import br.kauan.pix.internal.v1.PaymentStatusReport;
import br.kauan.pix.internal.v1.StatusReason;
import br.kauan.spi.adapter.input.kafka.infrastructure.dlq.DlqPublisher;
import br.kauan.spi.adapter.input.kafka.infrastructure.error.InfrastructureUnavailableException;
import br.kauan.spi.adapter.input.kafka.internal.InternalPaymentMessageMapper;
import br.kauan.spi.domain.entity.security.AuthenticatedPaymentRequest;
import br.kauan.spi.domain.entity.security.AuthenticatedStatusReport;
import br.kauan.spi.domain.entity.status.IncomingStatusReportCommand;
import br.kauan.spi.domain.entity.status.StatusReasonCode;
import br.kauan.spi.domain.entity.status.StatusReportOutcome;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.port.input.PaymentTransactionProcessorUseCase;
import br.kauan.spi.port.input.StatusReportProcessingResult;
import br.kauan.spi.port.output.PaymentTransactionPersistenceResult;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.Acknowledgment;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class PaymentMessageConsumerTest {

    @Test
    void kafkaCallbacksRecordBatchSizeProcessingDurationAndIdleGapInJfr(@TempDir Path tempDir) throws Exception {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        var paymentResult = new PaymentTransactionPersistenceResult(
                List.of(), List.of(), List.of(), List.of(), List.of());
        when(processor.processTransactions(any(List.class))).thenAnswer(invocation -> {
            Thread.sleep(20);
            return paymentResult;
        });
        stubNoDivergentStatusReports(processor);
        PaymentMessageConsumer consumer = consumer(processor);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        Path recordingFile = tempDir.resolve("kafka-batches.jfr");

        try (Recording recording = new Recording(Configuration.getConfiguration("profile"))) {
            recording.start();
            consumer.consumePaymentRequests(List.of(
                    paymentRequestRecord("E2E-JFR-1", "123", "12"),
                    paymentRequestRecord("E2E-JFR-2", "456", "34"),
                    paymentRequestRecord("E2E-JFR-3", "789", "56")
            ), acknowledgment);
            Thread.sleep(30);
            consumer.consumePaymentRequests(List.of(
                    paymentRequestRecord("E2E-JFR-4", "123", "12")
            ), acknowledgment);
            consumer.consumeStatusReports(List.of(
                    statusReportRecord("E2E-JFR-1", PaymentStatus.ACCEPTED_IN_PROCESS),
                    statusReportRecord("E2E-JFR-2", PaymentStatus.ACCEPTED_IN_PROCESS)
            ), acknowledgment);
            recording.stop();
            recording.dump(recordingFile);
        }

        var batchEvents = RecordingFile.readAllEvents(recordingFile).stream()
                .filter(event -> event.getEventType().getName().equals("br.kauan.spi.KafkaBatchReceived"))
                .toList();

        assertThat(batchEvents)
                .extracting(
                        event -> event.getString("topic"),
                        event -> event.getInt("recordCount"))
                .containsExactlyInAnyOrder(
                        tuple("spi-payment-requests", 3),
                        tuple("spi-payment-requests", 1),
                        tuple("spi-payment-status-reports", 2)
                );
        var paymentRequestEvents = batchEvents.stream()
                .filter(event -> event.getString("topic").equals("spi-payment-requests"))
                .sorted((left, right) -> left.getStartTime().compareTo(right.getStartTime()))
                .toList();

        assertThat(paymentRequestEvents)
                .allSatisfy(event -> assertThat(event.getDuration())
                        .isGreaterThanOrEqualTo(Duration.ofMillis(15)));
        assertThat(Duration.between(
                paymentRequestEvents.getFirst().getEndTime(),
                paymentRequestEvents.getLast().getStartTime()
        )).isGreaterThanOrEqualTo(Duration.ofMillis(20));
    }

    @Test
    void paymentAndStatusListenersUseSeparateConsumerGroupsAndManualAck() throws Exception {
        KafkaListener paymentListener = PaymentMessageConsumer.class
                .getMethod("consumePaymentRequests", List.class, Acknowledgment.class)
                .getAnnotation(KafkaListener.class);
        KafkaListener statusListener = PaymentMessageConsumer.class
                .getMethod("consumeStatusReports", List.class, Acknowledgment.class)
                .getAnnotation(KafkaListener.class);

        assertEquals("${spi.kafka.payment-request-group-id}",
                paymentListener.groupId());
        assertEquals("${spi.kafka.status-report-group-id}",
                statusListener.groupId());
        assertEquals("paymentRequestKafkaListenerContainerFactory", paymentListener.containerFactory());
        assertEquals("statusReportKafkaListenerContainerFactory", statusListener.containerFactory());
    }

    @Test
    void missingAuthenticationTakesPrecedenceOverInvalidPayload() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        DeadLetterPublishingRecoverer dlqRecoverer = mock(DeadLetterPublishingRecoverer.class);
        PaymentMessageConsumer consumer = consumer(processor, dlqRecoverer);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        ConsumerRecord<String, byte[]> record = recordWithoutAuthenticatedIspb(
                "spi-payment-requests",
                0,
                1L,
                "not-protobuf".getBytes(StandardCharsets.UTF_8)
        );

        consumer.consumePaymentRequests(List.of(record), acknowledgment);

        verify(dlqRecoverer).accept(
                eq(record),
                isNull(),
                any(NotAuthenticatedException.class)
        );
        verify(dlqRecoverer, never()).accept(
                any(),
                any(),
                any(InvalidInboundPayloadException.class)
        );
        verify(processor, never()).processTransactions(any(List.class));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void authenticationFailureForOneRecordDoesNotBlockValidBatchRecords() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        stubNoDivergentDuplicates(processor);
        DeadLetterPublishingRecoverer notAuthenticatedRecoverer = mock(DeadLetterPublishingRecoverer.class);
        PaymentMessageConsumer consumer = consumer(processor, notAuthenticatedRecoverer);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        ConsumerRecord<String, byte[]> unauthenticated = recordWithoutAuthenticatedIspb(
                "spi-payment-requests",
                0,
                1L,
                paymentRequest("E2E-NO-AUTH", "123", "12").toByteArray()
        );
        ConsumerRecord<String, byte[]> valid = paymentRequestRecord("E2E-VALID", "456", "34");

        consumer.consumePaymentRequests(List.of(unauthenticated, valid), acknowledgment);

        verify(notAuthenticatedRecoverer).accept(
                eq(unauthenticated),
                isNull(),
                any(NotAuthenticatedException.class)
        );
        var requestsCaptor = forClass(List.class);
        verify(processor).processTransactions(requestsCaptor.capture());
        @SuppressWarnings("unchecked")
        List<AuthenticatedPaymentRequest> requests = requestsCaptor.getValue();
        assertThat(requests)
                .extracting(request -> request.command().getPaymentId())
                .containsExactly("E2E-VALID");
        assertThat(requests.getFirst().sourceOrdinal()).isEqualTo(1);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void paymentRequestAuthorizationIsOwnedByTheProcessingBoundary() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        DeadLetterPublishingRecoverer unauthorizedRecoverer = mock(DeadLetterPublishingRecoverer.class);
        PaymentMessageConsumer consumer = consumer(processor, unauthorizedRecoverer);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        ConsumerRecord<String, byte[]> record =
                withAuthenticatedIspb(paymentRequestRecord("E2E-WRONG-SENDER", "123", "12"), "33333333");
        when(processor.processTransactions(any(List.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<AuthenticatedPaymentRequest> requests = invocation.getArgument(0);
            return new PaymentTransactionPersistenceResult(
                    List.of(), List.of(), List.of(), List.of(), List.of(requests.getFirst()));
        });

        consumer.consumePaymentRequests(List.of(record), acknowledgment);

        var inOrder = inOrder(processor, unauthorizedRecoverer, acknowledgment);
        inOrder.verify(processor).processTransactions(any(List.class));
        inOrder.verify(unauthorizedRecoverer).accept(
                eq(record),
                isNull(),
                any(UnauthorizedPspException.class)
        );
        inOrder.verify(acknowledgment).acknowledge();
    }

    @Test
    void unauthorizedPersistenceResultPublishesOnlyItsOriginalRecord() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        DeadLetterPublishingRecoverer unauthorizedRecoverer = mock(DeadLetterPublishingRecoverer.class);
        PaymentMessageConsumer consumer = consumer(processor, unauthorizedRecoverer);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        ConsumerRecord<String, byte[]> first = paymentRequestRecord("E2E-SAME", "123", "12");
        ConsumerRecord<String, byte[]> second = paymentRequestRecord("E2E-SAME", "456", "34");
        when(processor.processTransactions(any(List.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<AuthenticatedPaymentRequest> requests = invocation.getArgument(0);
            return new PaymentTransactionPersistenceResult(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(requests.get(1))
            );
        });

        consumer.consumePaymentRequests(List.of(first, second), acknowledgment);

        verify(unauthorizedRecoverer).accept(
                eq(second),
                isNull(),
                any(UnauthorizedPspException.class)
        );
        verify(unauthorizedRecoverer, never()).accept(
                eq(first),
                isNull(),
                any(UnauthorizedPspException.class)
        );
        verify(acknowledgment).acknowledge();
    }

    @Test
    void authenticationDlqFailurePreventsAck() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        DeadLetterPublishingRecoverer notAuthenticatedRecoverer = mock(DeadLetterPublishingRecoverer.class);
        doThrow(new IllegalStateException("security dlq failed"))
                .when(notAuthenticatedRecoverer).accept(any(), isNull(), any());
        PaymentMessageConsumer consumer = consumer(processor, notAuthenticatedRecoverer);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        ConsumerRecord<String, byte[]> record = recordWithoutAuthenticatedIspb(
                "spi-payment-requests",
                0,
                1L,
                new byte[0]
        );

        assertThrows(
                IllegalStateException.class,
                () -> consumer.consumePaymentRequests(List.of(record), acknowledgment)
        );

        verify(acknowledgment, never()).acknowledge();
        verify(processor, never()).processTransactions(any(List.class));
    }

    @Test
    void consumePaymentRequestsProcessesInternalProtobufMessage() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        stubNoDivergentDuplicates(processor);
        PaymentMessageConsumer consumer = consumer(processor);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        consumer.consumePaymentRequests(List.of(paymentRequestRecord("E2E-1", "000123", "0012")), acknowledgment);

        var paymentsCaptor = forClass(List.class);
        verify(processor).processTransactions(paymentsCaptor.capture());
        @SuppressWarnings("unchecked")
        List<AuthenticatedPaymentRequest> payments = paymentsCaptor.getValue();
        PaymentTransactionCommand payment = payments.getFirst().command();
        assertEquals("E2E-1", payment.getPaymentId());
        assertEquals(1234L, payment.getAmountCents());
        assertEquals("BRL", payment.getCurrency());
        assertEquals("10000001", payment.getSender().getAccount().getBankCode());
        assertEquals("000123", payment.getSender().getAccount().getNumber());
        assertEquals("0012", payment.getSender().getAccount().getBranch());
        assertEquals("20000001", payment.getReceiver().getAccount().getBankCode());
        assertEquals("+5511999999999", payment.getReceiver().getPixKey());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void divergentDuplicatePublishesOriginalRecordToDlqBeforeAck() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        DeadLetterPublishingRecoverer divergentDuplicateRecoverer = mock(DeadLetterPublishingRecoverer.class);
        PaymentMessageConsumer consumer = consumer(processor, divergentDuplicateRecoverer);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        ConsumerRecord<String, byte[]> record = paymentRequestRecord("E2E-DIVERGENT", "123", "12");
        PaymentTransactionCommand divergent = paymentTransaction("E2E-DIVERGENT");
        when(processor.processTransactions(any(List.class))).thenReturn(new PaymentTransactionPersistenceResult(
                List.of(),
                List.of(),
                List.of(),
                List.of(new AuthenticatedPaymentRequest(0, "10000001", divergent)),
                List.of()
        ));

        consumer.consumePaymentRequests(List.of(record), acknowledgment);

        var inOrder = inOrder(processor, divergentDuplicateRecoverer, acknowledgment);
        inOrder.verify(processor).processTransactions(any(List.class));
        inOrder.verify(divergentDuplicateRecoverer).accept(
                eq(record),
                isNull(),
                any(DivergentDuplicatePaymentException.class));
        inOrder.verify(acknowledgment).acknowledge();
    }

    @Test
    void divergentDuplicateDlqFailurePreventsAckAndPropagatesError() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        DeadLetterPublishingRecoverer divergentDuplicateRecoverer = mock(DeadLetterPublishingRecoverer.class);
        PaymentMessageConsumer consumer = consumer(processor, divergentDuplicateRecoverer);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        ConsumerRecord<String, byte[]> record = paymentRequestRecord("E2E-DIVERGENT", "123", "12");
        when(processor.processTransactions(any(List.class))).thenReturn(new PaymentTransactionPersistenceResult(
                List.of(),
                List.of(),
                List.of(),
                List.of(new AuthenticatedPaymentRequest(
                        0,
                        "10000001",
                        paymentTransaction("E2E-DIVERGENT")
                )),
                List.of()
        ));
        doThrow(new IllegalStateException("dlq failed"))
                .when(divergentDuplicateRecoverer).accept(any(), isNull(), any());

        assertThrows(IllegalStateException.class,
                () -> consumer.consumePaymentRequests(List.of(record), acknowledgment));

        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void consumePaymentRequestsPassesKafkaRecordsAsTransactionList() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        stubNoDivergentDuplicates(processor);
        PaymentMessageConsumer consumer = consumer(processor);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        consumer.consumePaymentRequests(List.of(
                paymentRequestRecord("E2E-1", "123", "12"),
                paymentRequestRecord("E2E-2", "456", "34")
        ), acknowledgment);

        var paymentsCaptor = forClass(List.class);
        verify(processor).processTransactions(paymentsCaptor.capture());
        @SuppressWarnings("unchecked")
        List<AuthenticatedPaymentRequest> payments = paymentsCaptor.getValue();
        assertEquals(List.of("E2E-1", "E2E-2"), payments.stream()
                .map(payment -> payment.command().getPaymentId())
                .toList());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void invalidPaymentRequestGoesToDlqAndBatchContinues() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        stubNoDivergentDuplicates(processor);
        DeadLetterPublishingRecoverer invalidPayloadRecoverer = mock(DeadLetterPublishingRecoverer.class);
        PaymentMessageConsumer consumer = consumer(processor, invalidPayloadRecoverer);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        ConsumerRecord<String, byte[]> invalidRecord = record("spi-payment-requests", 3, 41L, "raw-invalid".getBytes());
        ConsumerRecord<String, byte[]> validRecord = paymentRequestRecord("E2E-1", "123", "12");

        consumer.consumePaymentRequests(List.of(invalidRecord, validRecord), acknowledgment);

        verify(invalidPayloadRecoverer).accept(
                eq(invalidRecord),
                isNull(),
                any(RuntimeException.class));
        verify(processor).processTransactions(any(List.class));
        var inOrder = inOrder(invalidPayloadRecoverer, processor, acknowledgment);
        inOrder.verify(invalidPayloadRecoverer).accept(
                eq(invalidRecord),
                isNull(),
                any(RuntimeException.class));
        inOrder.verify(processor).processTransactions(any(List.class));
        inOrder.verify(acknowledgment).acknowledge();
    }

    @Test
    void semanticallyInvalidPaymentRequestGoesToDlqAndBatchContinues() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        stubNoDivergentDuplicates(processor);
        DeadLetterPublishingRecoverer invalidPayloadRecoverer = mock(DeadLetterPublishingRecoverer.class);
        PaymentMessageConsumer consumer = consumer(processor, invalidPayloadRecoverer);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        PaymentRequest base = paymentRequest("E2E-INVALID-ACCOUNT-TYPE", "123", "12");
        PaymentRequest invalid = base.toBuilder()
                .setSender(base.getSender().toBuilder()
                        .setAccount(base.getSender().getAccount().toBuilder().setType("CRYPTO")))
                .build();
        ConsumerRecord<String, byte[]> invalidRecord = record(
                "spi-payment-requests", 3, 42L, invalid.toByteArray());
        ConsumerRecord<String, byte[]> validRecord = paymentRequestRecord("E2E-VALID", "456", "34");

        consumer.consumePaymentRequests(List.of(invalidRecord, validRecord), acknowledgment);

        verify(invalidPayloadRecoverer).accept(
                eq(invalidRecord),
                isNull(),
                any(InvalidInboundPayloadException.class));
        var requestsCaptor = forClass(List.class);
        verify(processor).processTransactions(requestsCaptor.capture());
        @SuppressWarnings("unchecked")
        List<AuthenticatedPaymentRequest> requests = requestsCaptor.getValue();
        assertThat(requests)
                .extracting(request -> request.command().getPaymentId())
                .containsExactly("E2E-VALID");
        verify(acknowledgment).acknowledge();
    }

    @Test
    void paymentRequestDlqFailurePreventsAckAndPropagatesError() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        DeadLetterPublishingRecoverer invalidPayloadRecoverer = mock(DeadLetterPublishingRecoverer.class);
        PaymentMessageConsumer consumer = consumer(processor, invalidPayloadRecoverer);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        ConsumerRecord<String, byte[]> invalidRecord = record("spi-payment-requests", 0, 10L, "raw-invalid".getBytes());
        doThrow(new IllegalStateException("dlq failed"))
                .when(invalidPayloadRecoverer).accept(any(), isNull(), any());

        assertThrows(IllegalStateException.class,
                () -> consumer.consumePaymentRequests(List.of(invalidRecord), acknowledgment));

        verify(acknowledgment, never()).acknowledge();
        verify(processor, never()).processTransactions(any(List.class));
    }

    @Test
    void emptyPaymentRequestPayloadGoesToDlqAndBatchContinues() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        stubNoDivergentDuplicates(processor);
        DeadLetterPublishingRecoverer invalidPayloadRecoverer = mock(DeadLetterPublishingRecoverer.class);
        PaymentMessageConsumer consumer = consumer(processor, invalidPayloadRecoverer);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        ConsumerRecord<String, byte[]> emptyRecord = record("spi-payment-requests", 1, 11L, new byte[0]);
        ConsumerRecord<String, byte[]> validRecord = paymentRequestRecord("E2E-1", "123", "12");

        consumer.consumePaymentRequests(List.of(emptyRecord, validRecord), acknowledgment);

        verify(invalidPayloadRecoverer).accept(eq(emptyRecord), isNull(), any(RuntimeException.class));
        verify(processor).processTransactions(any(List.class));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void paymentRequestMapperFailureIsBatchLevelFailureNotInvalidPayload() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        InternalPaymentMessageMapper mapper = mock(InternalPaymentMessageMapper.class);
        DeadLetterPublishingRecoverer invalidPayloadRecoverer = mock(DeadLetterPublishingRecoverer.class);
        RuntimeException mapperFailure = new RuntimeException("mapper failed");
        doThrow(mapperFailure).when(mapper).toPaymentTransaction(any(PaymentRequest.class));
        PaymentMessageConsumer consumer = consumer(processor, mapper, invalidPayloadRecoverer);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> consumer.consumePaymentRequests(
                        List.of(paymentRequestRecord("E2E-1", "123", "12")),
                        acknowledgment));

        assertThat(exception).isSameAs(mapperFailure);
        verify(invalidPayloadRecoverer, never()).accept(any(), any(), any());
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void paymentRequestProcessingFailureIsTreatedAsBatchLevelFailure() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        RuntimeException processingFailure = new RuntimeException("processing failed");
        doThrow(processingFailure)
                .when(processor).processTransactions(any(List.class));
        PaymentMessageConsumer consumer = consumer(processor);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        ConsumerRecord<String, byte[]> record = paymentRequestRecord("E2E-1", "123", "12");

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> consumer.consumePaymentRequests(List.of(record), acknowledgment));

        assertThat(exception).isSameAs(processingFailure);
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void paymentRequestDatabaseConnectionFailureIsMappedToInfrastructureUnavailable() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        CannotGetJdbcConnectionException databaseFailure = new CannotGetJdbcConnectionException("db down");
        doThrow(databaseFailure)
                .when(processor).processTransactions(any(List.class));
        PaymentMessageConsumer consumer = consumer(processor);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        InfrastructureUnavailableException exception = assertThrows(InfrastructureUnavailableException.class,
                () -> consumer.consumePaymentRequests(
                        List.of(paymentRequestRecord("E2E-DB-DOWN", "123", "12")),
                        acknowledgment));

        assertThat(exception)
                .hasMessage("Database unavailable while processing SPI batch")
                .hasCause(databaseFailure);
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void paymentRequestTransientLockFailureIsMappedToInfrastructureUnavailable() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        CannotAcquireLockException databaseFailure = new CannotAcquireLockException("lock unavailable");
        doThrow(databaseFailure).when(processor).processTransactions(any(List.class));
        PaymentMessageConsumer consumer = consumer(processor);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        InfrastructureUnavailableException exception = assertThrows(InfrastructureUnavailableException.class,
                () -> consumer.consumePaymentRequests(
                        List.of(paymentRequestRecord("E2E-LOCKED", "123", "12")),
                        acknowledgment));

        assertThat(exception).hasCause(databaseFailure);
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void paymentRequestBatchProcessingFailureDoesNotBlameFirstRecord() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        RuntimeException processingFailure = new RuntimeException("processing failed");
        doThrow(processingFailure)
                .when(processor).processTransactions(any(List.class));
        PaymentMessageConsumer consumer = consumer(processor);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        ConsumerRecord<String, byte[]> firstRecord = paymentRequestRecord("E2E-1", "123", "12");
        ConsumerRecord<String, byte[]> secondRecord = paymentRequestRecord("E2E-2", "456", "34");

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> consumer.consumePaymentRequests(List.of(firstRecord, secondRecord), acknowledgment));

        assertThat(exception).isSameAs(processingFailure);
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void consumeStatusReportsProcessesInternalProtobufMessage() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        stubNoDivergentStatusReports(processor);
        PaymentMessageConsumer consumer = consumer(processor);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        consumer.consumeStatusReports(
                List.of(statusReportRecord("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS)),
                acknowledgment);

        var statusReportsCaptor = forClass(List.class);
        verify(processor).processStatusReports(statusReportsCaptor.capture());
        @SuppressWarnings("unchecked")
        List<AuthenticatedStatusReport> statusReports = statusReportsCaptor.getValue();
        IncomingStatusReportCommand statusReport = statusReports.getFirst().command();
        assertEquals("E2E-1", statusReport.originalPaymentId());
        assertEquals(StatusReportOutcome.ACCEPTED, statusReport.outcome());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consumeStatusReportsPassesKafkaRecordsAsStatusReportList() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        stubNoDivergentStatusReports(processor);
        PaymentMessageConsumer consumer = consumer(processor);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        consumer.consumeStatusReports(List.of(
                statusReportRecord("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS),
                statusReportRecord("E2E-2", PaymentStatus.REJECTED)
        ), acknowledgment);

        var statusReportsCaptor = forClass(List.class);
        verify(processor).processStatusReports(statusReportsCaptor.capture());
        @SuppressWarnings("unchecked")
        List<AuthenticatedStatusReport> statusReports = statusReportsCaptor.getValue();
        assertEquals(List.of("E2E-1", "E2E-2"), statusReports.stream()
                .map(statusReport -> statusReport.command().originalPaymentId())
                .toList());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void divergentStatusReportPublishesOriginalRecordToDlqBeforeAck() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        DeadLetterPublishingRecoverer divergentStatusRecoverer = mock(DeadLetterPublishingRecoverer.class);
        PaymentMessageConsumer consumer = consumer(processor, divergentStatusRecoverer);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        ConsumerRecord<String, byte[]> record = statusReportRecord("E2E-DIVERGENT", PaymentStatus.REJECTED);
        when(processor.processStatusReports(any(List.class))).thenReturn(new StatusReportProcessingResult(
                List.of(new AuthenticatedStatusReport(
                        0,
                        "20000001",
                        new IncomingStatusReportCommand(
                                "E2E-DIVERGENT",
                                StatusReportOutcome.REJECTED,
                                List.of(StatusReasonCode.of("AB03"))
                        )
                )),
                List.of()
        ));

        consumer.consumeStatusReports(List.of(record), acknowledgment);

        var inOrder = inOrder(processor, divergentStatusRecoverer, acknowledgment);
        inOrder.verify(processor).processStatusReports(any(List.class));
        inOrder.verify(divergentStatusRecoverer).accept(
                eq(record),
                isNull(),
                any(StatusReportConflictException.class));
        inOrder.verify(acknowledgment).acknowledge();
    }

    @Test
    void divergentStatusReportDlqFailurePreventsAckAndPropagatesError() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        DeadLetterPublishingRecoverer divergentStatusRecoverer = mock(DeadLetterPublishingRecoverer.class);
        PaymentMessageConsumer consumer = consumer(processor, divergentStatusRecoverer);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        ConsumerRecord<String, byte[]> record = statusReportRecord("E2E-DIVERGENT", PaymentStatus.REJECTED);
        when(processor.processStatusReports(any(List.class))).thenReturn(new StatusReportProcessingResult(
                List.of(new AuthenticatedStatusReport(
                        0,
                        "20000001",
                        new IncomingStatusReportCommand(
                                "E2E-DIVERGENT",
                                StatusReportOutcome.REJECTED,
                                List.of(StatusReasonCode.of("AB03"))
                        )
                )),
                List.of()
        ));
        doThrow(new IllegalStateException("dlq failed"))
                .when(divergentStatusRecoverer).accept(any(), isNull(), any());

        assertThrows(IllegalStateException.class,
                () -> consumer.consumeStatusReports(List.of(record), acknowledgment));

        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void unauthorizedStatusReportPublishesOriginalRecordToSecurityDlqBeforeAck() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        DeadLetterPublishingRecoverer unauthorizedRecoverer = mock(DeadLetterPublishingRecoverer.class);
        PaymentMessageConsumer consumer = consumer(processor, unauthorizedRecoverer);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        ConsumerRecord<String, byte[]> record =
                statusReportRecord("E2E-UNAUTHORIZED", PaymentStatus.REJECTED);
        when(processor.processStatusReports(any(List.class))).thenReturn(new StatusReportProcessingResult(
                List.of(),
                List.of(new AuthenticatedStatusReport(
                        0,
                        "20000001",
                        new IncomingStatusReportCommand(
                                "E2E-UNAUTHORIZED",
                                StatusReportOutcome.REJECTED,
                                List.of(StatusReasonCode.of("AB03"))
                        )
                ))
        ));

        consumer.consumeStatusReports(List.of(record), acknowledgment);

        var inOrder = inOrder(processor, unauthorizedRecoverer, acknowledgment);
        inOrder.verify(processor).processStatusReports(any(List.class));
        inOrder.verify(unauthorizedRecoverer).accept(
                eq(record),
                isNull(),
                any(UnauthorizedPspException.class)
        );
        inOrder.verify(acknowledgment).acknowledge();
    }

    @Test
    void consumeStatusReportsProcessingFailureIsTreatedAsBatchLevelFailure() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        RuntimeException processingFailure = new RuntimeException("processing failed");
        doThrow(processingFailure)
                .when(processor).processStatusReports(any(List.class));
        PaymentMessageConsumer consumer = consumer(processor);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        ConsumerRecord<String, byte[]> record = statusReportRecord("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> consumer.consumeStatusReports(List.of(record), acknowledgment));

        assertThat(exception).isSameAs(processingFailure);
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void statusReportDatabaseResourceFailureIsMappedToInfrastructureUnavailable() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        DataAccessResourceFailureException databaseFailure =
                new DataAccessResourceFailureException("db unavailable");
        doThrow(databaseFailure)
                .when(processor).processStatusReports(any(List.class));
        PaymentMessageConsumer consumer = consumer(processor);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        InfrastructureUnavailableException exception = assertThrows(InfrastructureUnavailableException.class,
                () -> consumer.consumeStatusReports(
                        List.of(statusReportRecord("E2E-DB-DOWN", PaymentStatus.ACCEPTED_IN_PROCESS)),
                        acknowledgment));

        assertThat(exception)
                .hasMessage("Database unavailable while processing SPI batch")
                .hasCause(databaseFailure);
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void statusReportTransientQueryFailureIsMappedToInfrastructureUnavailable() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        QueryTimeoutException databaseFailure = new QueryTimeoutException("query timed out");
        doThrow(databaseFailure).when(processor).processStatusReports(any(List.class));
        PaymentMessageConsumer consumer = consumer(processor);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        InfrastructureUnavailableException exception = assertThrows(InfrastructureUnavailableException.class,
                () -> consumer.consumeStatusReports(
                        List.of(statusReportRecord("E2E-QUERY-TIMEOUT", PaymentStatus.ACCEPTED_IN_PROCESS)),
                        acknowledgment));

        assertThat(exception).hasCause(databaseFailure);
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void invalidStatusReportGoesToDlq() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        DeadLetterPublishingRecoverer invalidPayloadRecoverer = mock(DeadLetterPublishingRecoverer.class);
        PaymentMessageConsumer consumer = consumer(processor, invalidPayloadRecoverer);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        ConsumerRecord<String, byte[]> invalidRecord = record(
                "spi-payment-status-reports",
                5,
                91L,
                "raw-invalid".getBytes());

        consumer.consumeStatusReports(List.of(invalidRecord), acknowledgment);

        verify(invalidPayloadRecoverer).accept(
                eq(invalidRecord),
                isNull(),
                any(RuntimeException.class));
        verify(processor, never()).processStatusReports(any(List.class));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void rejectedStatusReportWithoutAReasonCodeGoesToDlqBeforeProcessing() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        DeadLetterPublishingRecoverer invalidPayloadRecoverer = mock(DeadLetterPublishingRecoverer.class);
        PaymentMessageConsumer consumer = consumer(processor, invalidPayloadRecoverer);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        ConsumerRecord<String, byte[]> invalidRecord = record(
                "spi-payment-status-reports",
                5,
                93L,
                PaymentStatusReport.newBuilder()
                        .setPaymentId("E2E-MISSING-REASON")
                        .setStatus(PaymentStatus.REJECTED)
                        .build()
                        .toByteArray()
        );

        consumer.consumeStatusReports(List.of(invalidRecord), acknowledgment);

        verify(invalidPayloadRecoverer).accept(
                eq(invalidRecord),
                isNull(),
                any(InvalidInboundPayloadException.class)
        );
        verify(processor, never()).processStatusReports(anyList());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void statusReportDlqFailurePreventsAckAndPropagatesError() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        DeadLetterPublishingRecoverer invalidPayloadRecoverer = mock(DeadLetterPublishingRecoverer.class);
        PaymentMessageConsumer consumer = consumer(processor, invalidPayloadRecoverer);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        ConsumerRecord<String, byte[]> invalidRecord = record(
                "spi-payment-status-reports",
                5,
                91L,
                "raw-invalid".getBytes());
        doThrow(new IllegalStateException("dlq failed"))
                .when(invalidPayloadRecoverer).accept(any(), isNull(), any());

        assertThrows(IllegalStateException.class,
                () -> consumer.consumeStatusReports(List.of(invalidRecord), acknowledgment));

        verify(acknowledgment, never()).acknowledge();
        verify(processor, never()).processStatusReports(any(List.class));
    }

    @Test
    void nullStatusReportPayloadGoesToDlq() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        DeadLetterPublishingRecoverer invalidPayloadRecoverer = mock(DeadLetterPublishingRecoverer.class);
        PaymentMessageConsumer consumer = consumer(processor, invalidPayloadRecoverer);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        ConsumerRecord<String, byte[]> nullPayloadRecord = record("spi-payment-status-reports", 5, 92L, null);

        consumer.consumeStatusReports(List.of(nullPayloadRecord), acknowledgment);

        verify(invalidPayloadRecoverer).accept(eq(nullPayloadRecord), isNull(), any(RuntimeException.class));
        verify(processor, never()).processStatusReports(any(List.class));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void statusReportMapperFailureIsBatchLevelFailureNotInvalidPayload() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        InternalPaymentMessageMapper mapper = mock(InternalPaymentMessageMapper.class);
        DeadLetterPublishingRecoverer invalidPayloadRecoverer = mock(DeadLetterPublishingRecoverer.class);
        RuntimeException mapperFailure = new RuntimeException("mapper failed");
        doThrow(mapperFailure).when(mapper).toStatusReport(any(PaymentStatusReport.class));
        PaymentMessageConsumer consumer = consumer(processor, mapper, invalidPayloadRecoverer);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> consumer.consumeStatusReports(
                        List.of(statusReportRecord("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS)),
                        acknowledgment));

        assertThat(exception).isSameAs(mapperFailure);
        verify(invalidPayloadRecoverer, never()).accept(any(), any(), any());
        verify(acknowledgment, never()).acknowledge();
    }

    private static PaymentMessageConsumer consumer(PaymentTransactionProcessorUseCase processor) {
        return consumer(processor, mock(DeadLetterPublishingRecoverer.class));
    }

    private static PaymentMessageConsumer consumer(
            PaymentTransactionProcessorUseCase processor,
            DeadLetterPublishingRecoverer recoverer
    ) {
        return consumer(processor, new InternalPaymentMessageMapper(), recoverer);
    }

    private static PaymentMessageConsumer consumer(
            PaymentTransactionProcessorUseCase processor,
            InternalPaymentMessageMapper mapper,
            DeadLetterPublishingRecoverer recoverer
    ) {
        return new PaymentMessageConsumer(
                new InboundPaymentMessageDecoder(mapper),
                processor,
                new DlqPublisher(recoverer)
        );
    }

    private static ConsumerRecord<String, byte[]> paymentRequestRecord(
            String paymentId,
            String accountNumber,
            String branch
    ) {
        return record("spi-payment-requests", 0, 15L, paymentRequest(paymentId, accountNumber, branch).toByteArray());
    }

    private static void stubNoDivergentDuplicates(PaymentTransactionProcessorUseCase processor) {
        when(processor.processTransactions(any(List.class)))
                .thenReturn(new PaymentTransactionPersistenceResult(
                        List.of(), List.of(), List.of(), List.of(), List.of()));
    }

    private static void stubNoDivergentStatusReports(PaymentTransactionProcessorUseCase processor) {
        when(processor.processStatusReports(any(List.class)))
                .thenReturn(new StatusReportProcessingResult(List.of(), List.of()));
    }

    private static ConsumerRecord<String, byte[]> statusReportRecord(String paymentId, PaymentStatus status) {
        return record("spi-payment-status-reports", 1, 25L, statusReport(paymentId, status).toByteArray());
    }

    private static ConsumerRecord<String, byte[]> record(String topic, int partition, long offset, byte[] value) {
        ConsumerRecord<String, byte[]> record =
                recordWithoutAuthenticatedIspb(topic, partition, offset, value);
        String authenticatedIspb =
                topic.equals("spi-payment-requests") ? "10000001" : "20000001";
        record.headers().add(
                AuthenticatedIspbHeaderExtractor.HEADER_NAME,
                authenticatedIspb.getBytes(StandardCharsets.UTF_8)
        );
        return record;
    }

    private static ConsumerRecord<String, byte[]> recordWithoutAuthenticatedIspb(
            String topic,
            int partition,
            long offset,
            byte[] value
    ) {
        return new ConsumerRecord<>(topic, partition, offset, "key-" + offset, value);
    }

    private static ConsumerRecord<String, byte[]> withAuthenticatedIspb(
            ConsumerRecord<String, byte[]> record,
            String authenticatedIspb
    ) {
        record.headers().remove(AuthenticatedIspbHeaderExtractor.HEADER_NAME);
        record.headers().add(
                AuthenticatedIspbHeaderExtractor.HEADER_NAME,
                authenticatedIspb.getBytes(StandardCharsets.UTF_8)
        );
        return record;
    }

    private static PaymentRequest paymentRequest(String paymentId, String accountNumber, String branch) {
        return PaymentRequest.newBuilder()
                .setPaymentId(paymentId)
                .setAmountCents(1234L)
                .setCurrency("BRL")
                .setDescription("Load test payment")
                .setSender(Party.newBuilder()
                        .setName("Sender")
                        .setTaxId("12345678900")
                        .setAccount(BankAccount.newBuilder()
                                .setNumber(accountNumber)
                                .setBranch(branch)
                                .setType("CHECKING")
                                .setIspb("10000001")
                                .build())
                        .build())
                .setReceiver(Party.newBuilder()
                        .setName("Receiver")
                        .setTaxId("98765432100")
                        .setPixKey("+5511999999999")
                        .setAccount(BankAccount.newBuilder()
                                .setNumber("456")
                                .setBranch("34")
                                .setType("CHECKING")
                                .setIspb("20000001")
                                .build())
                        .build())
                .build();
    }

    private static PaymentTransactionCommand paymentTransaction(String paymentId) {
        return PaymentTransactionCommand.builder()
                .paymentId(paymentId)
                .build();
    }

    private static PaymentStatusReport statusReport(String paymentId, PaymentStatus status) {
        PaymentStatusReport.Builder builder = PaymentStatusReport.newBuilder()
                .setPaymentId(paymentId)
                .setStatus(status);
        if (status == PaymentStatus.REJECTED) {
            builder.addReasons(StatusReason.newBuilder().setCode("AB03").build());
        }
        return builder.build();
    }
}
