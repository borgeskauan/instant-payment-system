package br.kauan.spi.adapter.input.kafka;

import br.kauan.pix.internal.v1.BankAccount;
import br.kauan.pix.internal.v1.Party;
import br.kauan.pix.internal.v1.PaymentRequest;
import br.kauan.pix.internal.v1.PaymentStatus;
import br.kauan.pix.internal.v1.PaymentStatusReport;
import br.kauan.spi.adapter.input.kafka.internal.InternalPaymentMessageMapper;
import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.domain.services.tracing.SpiTraceEvent;
import br.kauan.spi.domain.services.tracing.SpiTraceRecorder;
import br.kauan.spi.port.input.PaymentTransactionProcessorUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class PaymentMessageConsumerTest {

    @Test
    void consumerDoesNotExposeSingleRecordEntryPoints() {
        assertThrows(NoSuchMethodException.class,
                () -> PaymentMessageConsumer.class.getMethod("consumePaymentRequest", byte[].class));
        assertThrows(NoSuchMethodException.class,
                () -> PaymentMessageConsumer.class.getMethod("consumeStatusReport", byte[].class));
    }

    @Test
    void paymentAndStatusListenersUseSeparateConsumerGroups() throws Exception {
        KafkaListener paymentListener = PaymentMessageConsumer.class
                .getMethod("consumePaymentRequests", List.class)
                .getAnnotation(KafkaListener.class);
        KafkaListener statusListener = PaymentMessageConsumer.class
                .getMethod("consumeStatusReports", List.class, Acknowledgment.class)
                .getAnnotation(KafkaListener.class);

        assertEquals("${spi.kafka.payment-request-group-id:spi-payment-request-consumer-group}",
                paymentListener.groupId());
        assertEquals("${spi.kafka.status-report-group-id:spi-status-report-consumer-group}",
                statusListener.groupId());
    }

    @Test
    void consumePaymentRequestsProcessesInternalProtobufMessage() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        PaymentMessageConsumer consumer = consumer(processor, traceRecorder);

        consumer.consumePaymentRequests(List.of(paymentRequest("E2E-1", "000123", "0012").toByteArray()));

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
    }

    @Test
    void consumePaymentRequestsRecordsTraceWhenRecordIsConsumed() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        PaymentMessageConsumer consumer = consumer(processor, traceRecorder);

        consumer.consumePaymentRequests(List.of(paymentRequest("E2E-1", "123", "12").toByteArray()));

        var inOrder = inOrder(traceRecorder, processor);
        inOrder.verify(traceRecorder).record("E2E-1", SpiTraceEvent.REQUEST_CONSUMED);
        inOrder.verify(processor).processTransactions(any(List.class));
        verifyNoMoreInteractions(traceRecorder);
    }

    @Test
    void consumePaymentRequestsPassesKafkaRecordsAsTransactionList() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        PaymentMessageConsumer consumer = consumer(processor, mock(SpiTraceRecorder.class));

        consumer.consumePaymentRequests(List.of(
                paymentRequest("E2E-1", "123", "12").toByteArray(),
                paymentRequest("E2E-2", "456", "34").toByteArray()
        ));

        var paymentsCaptor = forClass(List.class);
        verify(processor).processTransactions(paymentsCaptor.capture());
        @SuppressWarnings("unchecked")
        List<PaymentTransactionCommand> payments = paymentsCaptor.getValue();
        assertEquals(List.of("E2E-1", "E2E-2"), payments.stream()
                .map(PaymentTransactionCommand::getPaymentId)
                .toList());
    }

    @Test
    void consumeStatusReportsProcessesInternalProtobufMessage() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        PaymentMessageConsumer consumer = consumer(processor, traceRecorder);

        consumer.consumeStatusReports(List.of(statusReport("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS).toByteArray()));

        var statusReportsCaptor = forClass(List.class);
        verify(processor).processStatusReports(statusReportsCaptor.capture());
        @SuppressWarnings("unchecked")
        List<StatusReportCommand> statusReports = statusReportsCaptor.getValue();
        StatusReportCommand statusReport = statusReports.getFirst();
        assertEquals("E2E-1", statusReport.getOriginalPaymentId());
        assertEquals(br.kauan.spi.domain.entity.status.PaymentStatus.ACCEPTED_IN_PROCESS, statusReport.getStatus());
    }

    @Test
    void consumeStatusReportsRecordsTraceWhenRecordIsReadable() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        PaymentMessageConsumer consumer = consumer(processor, traceRecorder);

        consumer.consumeStatusReports(List.of(statusReport("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS).toByteArray()));

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
                statusReport("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS).toByteArray(),
                statusReport("E2E-2", PaymentStatus.REJECTED).toByteArray()
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
    void consumeStatusReportsDoesNotAcknowledgeWhenProcessingFails() {
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        doThrow(new RuntimeException("processing failed"))
                .when(processor).processStatusReports(any(List.class));
        PaymentMessageConsumer consumer = consumer(processor, mock(SpiTraceRecorder.class));
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        consumer.consumeStatusReports(List.of(
                statusReport("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS).toByteArray()
        ), acknowledgment);

        verify(acknowledgment, never()).acknowledge();
    }

    private static PaymentMessageConsumer consumer(
            PaymentTransactionProcessorUseCase processor,
            SpiTraceRecorder traceRecorder
    ) {
        return new PaymentMessageConsumer(new InternalPaymentMessageMapper(), processor, traceRecorder);
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

    private static PaymentStatusReport statusReport(String paymentId, PaymentStatus status) {
        return PaymentStatusReport.newBuilder()
                .setPaymentId(paymentId)
                .setStatus(status)
                .build();
    }
}
