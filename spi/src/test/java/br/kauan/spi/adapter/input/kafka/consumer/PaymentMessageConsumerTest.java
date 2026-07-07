package br.kauan.spi.adapter.input.kafka.consumer;

import br.kauan.pix.internal.v1.BankAccount;
import br.kauan.pix.internal.v1.Party;
import br.kauan.pix.internal.v1.PaymentRequest;
import br.kauan.pix.internal.v1.PaymentStatus;
import br.kauan.pix.internal.v1.PaymentStatusReport;
import br.kauan.spi.adapter.input.kafka.infrastructure.dlq.InvalidPayloadDlqPublisher;
import br.kauan.spi.adapter.input.kafka.infrastructure.dlq.DivergentDuplicateDlqPublisher;
import br.kauan.spi.adapter.input.kafka.infrastructure.error.InfrastructureUnavailableException;
import br.kauan.spi.adapter.input.kafka.internal.InternalPaymentMessageMapper;
import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.domain.services.tracing.SpiTraceEvent;
import br.kauan.spi.domain.services.tracing.SpiTraceRecorder;
import br.kauan.spi.port.input.PaymentTransactionProcessorUseCase;
import br.kauan.spi.port.output.PaymentTransactionPersistenceResult;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
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
    void consumerDoesNotExposeSingleRecordEntryPoints() {
        assertThrows(NoSuchMethodException.class,
                () -> PaymentMessageConsumer.class.getMethod("consumePaymentRequest", byte[].class));
        assertThrows(NoSuchMethodException.class,
                () -> PaymentMessageConsumer.class.getMethod("consumeStatusReport", byte[].class));
    }

    @Test
    void paymentAndStatusListenersUseSeparateConsumerGroupsAndManualAck() throws Exception {
        KafkaListener paymentListener = PaymentMessageConsumer.class
                .getMethod("consumePaymentRequests", List.class, Acknowledgment.class)
                .getAnnotation(KafkaListener.class);
        KafkaListener statusListener = PaymentMessageConsumer.class
                .getMethod("consumeStatusReports", List.class, Acknowledgment.class)
                .getAnnotation(KafkaListener.class);

        assertEquals("${spi.kafka.payment-request-group-id:spi-payment-request-consumer-group}",
                paymentListener.groupId());
        assertEquals("${spi.kafka.status-report-group-id:spi-status-report-consumer-group}",
                statusListener.groupId());
        assertEquals("spiKafkaListenerContainerFactory", paymentListener.containerFactory());
        assertEquals("spiKafkaListenerContainerFactory", statusListener.containerFactory());
    }

    @Test
    void consumePaymentRequestsProcessesInternalProtobufMessage() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        stubNoDivergentDuplicates(processor);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        PaymentMessageConsumer consumer = consumer(processor, traceRecorder);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        consumer.consumePaymentRequests(List.of(paymentRequestRecord("E2E-1", "000123", "0012")), acknowledgment);

        var paymentsCaptor = forClass(List.class);
        verify(processor).processTransactions(paymentsCaptor.capture());
        @SuppressWarnings("unchecked")
        List<PaymentTransactionCommand> payments = paymentsCaptor.getValue();
        PaymentTransactionCommand payment = payments.getFirst();
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
        PaymentMessageConsumer consumer = consumer(
                processor,
                mock(SpiTraceRecorder.class),
                mock(DeadLetterPublishingRecoverer.class),
                divergentDuplicateRecoverer
        );
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        ConsumerRecord<String, byte[]> record = paymentRequestRecord("E2E-DIVERGENT", "123", "12");
        PaymentTransactionCommand divergent = paymentTransaction("E2E-DIVERGENT");
        when(processor.processTransactions(any(List.class))).thenReturn(new PaymentTransactionPersistenceResult(
                List.of(),
                List.of(divergent)
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
        PaymentMessageConsumer consumer = consumer(
                processor,
                mock(SpiTraceRecorder.class),
                mock(DeadLetterPublishingRecoverer.class),
                divergentDuplicateRecoverer
        );
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        ConsumerRecord<String, byte[]> record = paymentRequestRecord("E2E-DIVERGENT", "123", "12");
        when(processor.processTransactions(any(List.class))).thenReturn(new PaymentTransactionPersistenceResult(
                List.of(),
                List.of(paymentTransaction("E2E-DIVERGENT"))
        ));
        doThrow(new IllegalStateException("dlq failed"))
                .when(divergentDuplicateRecoverer).accept(any(), isNull(), any());

        assertThrows(IllegalStateException.class,
                () -> consumer.consumePaymentRequests(List.of(record), acknowledgment));

        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void consumePaymentRequestsRecordsTraceWhenRecordIsConsumed() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        stubNoDivergentDuplicates(processor);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        PaymentMessageConsumer consumer = consumer(processor, traceRecorder);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        consumer.consumePaymentRequests(List.of(paymentRequestRecord("E2E-1", "123", "12")), acknowledgment);

        var inOrder = inOrder(traceRecorder, processor);
        inOrder.verify(traceRecorder).record("E2E-1", SpiTraceEvent.REQUEST_CONSUMED);
        inOrder.verify(processor).processTransactions(any(List.class));
        verifyNoMoreInteractions(traceRecorder);
    }

    @Test
    void consumePaymentRequestsPassesKafkaRecordsAsTransactionList() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        stubNoDivergentDuplicates(processor);
        PaymentMessageConsumer consumer = consumer(processor, mock(SpiTraceRecorder.class));
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        consumer.consumePaymentRequests(List.of(
                paymentRequestRecord("E2E-1", "123", "12"),
                paymentRequestRecord("E2E-2", "456", "34")
        ), acknowledgment);

        var paymentsCaptor = forClass(List.class);
        verify(processor).processTransactions(paymentsCaptor.capture());
        @SuppressWarnings("unchecked")
        List<PaymentTransactionCommand> payments = paymentsCaptor.getValue();
        assertEquals(List.of("E2E-1", "E2E-2"), payments.stream()
                .map(PaymentTransactionCommand::getPaymentId)
                .toList());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void invalidPaymentRequestGoesToDlqAndBatchContinues() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        stubNoDivergentDuplicates(processor);
        DeadLetterPublishingRecoverer invalidPayloadRecoverer = mock(DeadLetterPublishingRecoverer.class);
        PaymentMessageConsumer consumer = consumer(processor, mock(SpiTraceRecorder.class), invalidPayloadRecoverer);
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
    void paymentRequestDlqFailurePreventsAckAndPropagatesError() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        DeadLetterPublishingRecoverer invalidPayloadRecoverer = mock(DeadLetterPublishingRecoverer.class);
        PaymentMessageConsumer consumer = consumer(processor, mock(SpiTraceRecorder.class), invalidPayloadRecoverer);
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
        PaymentMessageConsumer consumer = consumer(processor, mock(SpiTraceRecorder.class), invalidPayloadRecoverer);
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
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        DeadLetterPublishingRecoverer invalidPayloadRecoverer = mock(DeadLetterPublishingRecoverer.class);
        RuntimeException mapperFailure = new RuntimeException("mapper failed");
        doThrow(mapperFailure).when(mapper).toPaymentTransaction(any(PaymentRequest.class));
        PaymentMessageConsumer consumer = consumer(processor, mapper, traceRecorder, invalidPayloadRecoverer);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> consumer.consumePaymentRequests(
                        List.of(paymentRequestRecord("E2E-1", "123", "12")),
                        acknowledgment));

        assertThat(exception).isSameAs(mapperFailure);
        verify(invalidPayloadRecoverer, never()).accept(any(), any(), any());
        verify(traceRecorder, never()).record(any(), any());
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void paymentRequestTraceFailureIsBatchLevelFailureNotInvalidPayload() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        DeadLetterPublishingRecoverer invalidPayloadRecoverer = mock(DeadLetterPublishingRecoverer.class);
        RuntimeException traceFailure = new RuntimeException("trace failed");
        doThrow(traceFailure).when(traceRecorder).record(eq("E2E-1"), eq(SpiTraceEvent.REQUEST_CONSUMED));
        PaymentMessageConsumer consumer = consumer(processor, traceRecorder, invalidPayloadRecoverer);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> consumer.consumePaymentRequests(
                        List.of(paymentRequestRecord("E2E-1", "123", "12")),
                        acknowledgment));

        assertThat(exception).isSameAs(traceFailure);
        verify(invalidPayloadRecoverer, never()).accept(any(), any(), any());
        verify(processor, never()).processTransactions(any(List.class));
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void forcedUnknownProcessingErrorIsBatchLevelFailureNotInvalidPayload() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        DeadLetterPublishingRecoverer invalidPayloadRecoverer = mock(DeadLetterPublishingRecoverer.class);
        PaymentMessageConsumer consumer = consumer(
                processor,
                new InternalPaymentMessageMapper(),
                traceRecorder,
                invalidPayloadRecoverer,
                mock(DeadLetterPublishingRecoverer.class),
                true);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> consumer.consumePaymentRequests(
                        List.of(paymentRequestRecord("E2E-FORCED-UNKNOWN", "123", "12")),
                        acknowledgment));

        assertThat(exception).hasMessage("forced unknown processing failure");
        verify(invalidPayloadRecoverer, never()).accept(any(), any(), any());
        verify(traceRecorder, never()).record(any(), any());
        verify(processor, never()).processTransactions(any(List.class));
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void paymentRequestProcessingFailureIsTreatedAsBatchLevelFailure() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        RuntimeException processingFailure = new RuntimeException("processing failed");
        doThrow(processingFailure)
                .when(processor).processTransactions(any(List.class));
        PaymentMessageConsumer consumer = consumer(processor, mock(SpiTraceRecorder.class));
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
        PaymentMessageConsumer consumer = consumer(processor, mock(SpiTraceRecorder.class));
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
    void paymentRequestBatchProcessingFailureDoesNotBlameFirstRecord() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        RuntimeException processingFailure = new RuntimeException("processing failed");
        doThrow(processingFailure)
                .when(processor).processTransactions(any(List.class));
        PaymentMessageConsumer consumer = consumer(processor, mock(SpiTraceRecorder.class));
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
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        PaymentMessageConsumer consumer = consumer(processor, traceRecorder);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        consumer.consumeStatusReports(
                List.of(statusReportRecord("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS)),
                acknowledgment);

        var statusReportsCaptor = forClass(List.class);
        verify(processor).processStatusReports(statusReportsCaptor.capture());
        @SuppressWarnings("unchecked")
        List<StatusReportCommand> statusReports = statusReportsCaptor.getValue();
        StatusReportCommand statusReport = statusReports.getFirst();
        assertEquals("E2E-1", statusReport.getOriginalPaymentId());
        assertEquals(br.kauan.spi.domain.entity.status.PaymentStatus.ACCEPTED_IN_PROCESS, statusReport.getStatus());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consumeStatusReportsRecordsTraceWhenRecordIsReadable() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        PaymentMessageConsumer consumer = consumer(processor, traceRecorder);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        consumer.consumeStatusReports(
                List.of(statusReportRecord("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS)),
                acknowledgment);

        var inOrder = inOrder(traceRecorder, processor);
        inOrder.verify(traceRecorder).record("E2E-1", SpiTraceEvent.STATUS_RECEIVED);
        inOrder.verify(processor).processStatusReports(any(List.class));
        verifyNoMoreInteractions(traceRecorder);
    }

    @Test
    void consumeStatusReportsPassesKafkaRecordsAsStatusReportList() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        PaymentMessageConsumer consumer = consumer(processor, mock(SpiTraceRecorder.class));
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        consumer.consumeStatusReports(List.of(
                statusReportRecord("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS),
                statusReportRecord("E2E-2", PaymentStatus.REJECTED)
        ), acknowledgment);

        var statusReportsCaptor = forClass(List.class);
        verify(processor).processStatusReports(statusReportsCaptor.capture());
        @SuppressWarnings("unchecked")
        List<StatusReportCommand> statusReports = statusReportsCaptor.getValue();
        assertEquals(List.of("E2E-1", "E2E-2"), statusReports.stream()
                .map(StatusReportCommand::getOriginalPaymentId)
                .toList());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consumeStatusReportsProcessingFailureIsTreatedAsBatchLevelFailure() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        RuntimeException processingFailure = new RuntimeException("processing failed");
        doThrow(processingFailure)
                .when(processor).processStatusReports(any(List.class));
        PaymentMessageConsumer consumer = consumer(processor, mock(SpiTraceRecorder.class));
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
        PaymentMessageConsumer consumer = consumer(processor, mock(SpiTraceRecorder.class));
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
    void invalidStatusReportGoesToDlq() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        DeadLetterPublishingRecoverer invalidPayloadRecoverer = mock(DeadLetterPublishingRecoverer.class);
        PaymentMessageConsumer consumer = consumer(processor, mock(SpiTraceRecorder.class), invalidPayloadRecoverer);
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
    void statusReportDlqFailurePreventsAckAndPropagatesError() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        DeadLetterPublishingRecoverer invalidPayloadRecoverer = mock(DeadLetterPublishingRecoverer.class);
        PaymentMessageConsumer consumer = consumer(processor, mock(SpiTraceRecorder.class), invalidPayloadRecoverer);
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
        PaymentMessageConsumer consumer = consumer(processor, mock(SpiTraceRecorder.class), invalidPayloadRecoverer);
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
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        DeadLetterPublishingRecoverer invalidPayloadRecoverer = mock(DeadLetterPublishingRecoverer.class);
        RuntimeException mapperFailure = new RuntimeException("mapper failed");
        doThrow(mapperFailure).when(mapper).toStatusReport(any(PaymentStatusReport.class));
        PaymentMessageConsumer consumer = consumer(processor, mapper, traceRecorder, invalidPayloadRecoverer);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> consumer.consumeStatusReports(
                        List.of(statusReportRecord("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS)),
                        acknowledgment));

        assertThat(exception).isSameAs(mapperFailure);
        verify(invalidPayloadRecoverer, never()).accept(any(), any(), any());
        verify(traceRecorder, never()).record(any(), any());
        verify(acknowledgment, never()).acknowledge();
    }

    private static PaymentMessageConsumer consumer(
            PaymentTransactionProcessorUseCase processor,
            SpiTraceRecorder traceRecorder
    ) {
        return consumer(processor, traceRecorder, mock(DeadLetterPublishingRecoverer.class));
    }

    private static PaymentMessageConsumer consumer(
            PaymentTransactionProcessorUseCase processor,
            SpiTraceRecorder traceRecorder,
            DeadLetterPublishingRecoverer invalidPayloadRecoverer
    ) {
        return consumer(
                processor,
                traceRecorder,
                invalidPayloadRecoverer,
                mock(DeadLetterPublishingRecoverer.class)
        );
    }

    private static PaymentMessageConsumer consumer(
            PaymentTransactionProcessorUseCase processor,
            SpiTraceRecorder traceRecorder,
            DeadLetterPublishingRecoverer invalidPayloadRecoverer,
            DeadLetterPublishingRecoverer divergentDuplicateRecoverer
    ) {
        return consumer(processor, new InternalPaymentMessageMapper(), traceRecorder, invalidPayloadRecoverer,
                divergentDuplicateRecoverer);
    }

    private static PaymentMessageConsumer consumer(
            PaymentTransactionProcessorUseCase processor,
            InternalPaymentMessageMapper mapper,
            SpiTraceRecorder traceRecorder,
            DeadLetterPublishingRecoverer invalidPayloadRecoverer
    ) {
        return consumer(
                processor,
                mapper,
                traceRecorder,
                invalidPayloadRecoverer,
                mock(DeadLetterPublishingRecoverer.class),
                false
        );
    }

    private static PaymentMessageConsumer consumer(
            PaymentTransactionProcessorUseCase processor,
            InternalPaymentMessageMapper mapper,
            SpiTraceRecorder traceRecorder,
            DeadLetterPublishingRecoverer invalidPayloadRecoverer,
            DeadLetterPublishingRecoverer divergentDuplicateRecoverer
    ) {
        return consumer(processor, mapper, traceRecorder, invalidPayloadRecoverer, divergentDuplicateRecoverer, false);
    }

    private static PaymentMessageConsumer consumer(
            PaymentTransactionProcessorUseCase processor,
            InternalPaymentMessageMapper mapper,
            SpiTraceRecorder traceRecorder,
            DeadLetterPublishingRecoverer invalidPayloadRecoverer,
            DeadLetterPublishingRecoverer divergentDuplicateRecoverer,
            boolean forceUnknownProcessingError
    ) {
        InboundPaymentMessageDecoder messageDecoder =
                new InboundPaymentMessageDecoder(mapper, traceRecorder, forceUnknownProcessingError);
        InvalidPayloadDlqPublisher invalidPayloadDlqPublisher =
                new InvalidPayloadDlqPublisher(invalidPayloadRecoverer);
        DivergentDuplicateDlqPublisher divergentDuplicateDlqPublisher =
                new DivergentDuplicateDlqPublisher(divergentDuplicateRecoverer);

        return new PaymentMessageConsumer(
                messageDecoder,
                processor,
                invalidPayloadDlqPublisher,
                divergentDuplicateDlqPublisher);
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
                .thenReturn(new PaymentTransactionPersistenceResult(List.of(), List.of()));
    }

    private static ConsumerRecord<String, byte[]> statusReportRecord(String paymentId, PaymentStatus status) {
        return record("spi-payment-status-reports", 1, 25L, statusReport(paymentId, status).toByteArray());
    }

    private static ConsumerRecord<String, byte[]> record(String topic, int partition, long offset, byte[] value) {
        return new ConsumerRecord<>(topic, partition, offset, "key-" + offset, value);
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
        return PaymentStatusReport.newBuilder()
                .setPaymentId(paymentId)
                .setStatus(status)
                .build();
    }
}
