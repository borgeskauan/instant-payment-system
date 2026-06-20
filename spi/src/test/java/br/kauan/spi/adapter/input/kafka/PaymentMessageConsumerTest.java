package br.kauan.spi.adapter.input.kafka;

import br.kauan.spi.adapter.input.dtos.pacs.PaymentTransactionMapper;
import br.kauan.spi.adapter.input.dtos.pacs.StatusReportMapper;
import br.kauan.spi.adapter.input.dtos.pacs.pacs002.FIToFIPaymentStatusReport;
import br.kauan.spi.adapter.input.dtos.pacs.pacs008.FIToFICustomerCreditTransfer;
import br.kauan.spi.domain.entity.status.StatusBatch;
import br.kauan.spi.domain.entity.status.StatusReport;
import br.kauan.spi.domain.entity.transfer.PaymentBatch;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.domain.services.tracing.SpiTraceEvent;
import br.kauan.spi.domain.services.tracing.SpiTraceRecorder;
import br.kauan.spi.port.input.PaymentTransactionProcessorUseCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentMessageConsumerTest {

    @Test
    void consumeMessageParsesPaymentTransactionJsonOnlyOnce() throws Exception {
        PaymentTransactionMapper paymentTransactionMapper = mock(PaymentTransactionMapper.class);
        StatusReportMapper statusReportMapper = mock(StatusReportMapper.class);
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        ObjectMapper objectMapper = spy(new ObjectMapper());
        PaymentMessageConsumer consumer = new PaymentMessageConsumer(
                paymentTransactionMapper,
                statusReportMapper,
                processor,
                traceRecorder
        );
        ReflectionTestUtils.setField(consumer, "objectMapper", objectMapper);
        PaymentBatch paymentBatch = PaymentBatch.builder()
                .transactions(List.of(PaymentTransaction.builder().paymentId("E2E-1").build()))
                .build();
        when(paymentTransactionMapper.fromRegulatoryRequest(any(FIToFICustomerCreditTransfer.class)))
                .thenReturn(paymentBatch);

        consumer.consumePaymentRequest("""
                {"CdtTrfTxInf":[]}
                """.getBytes(StandardCharsets.UTF_8));

        verify(objectMapper).readTree(any(byte[].class));
        verify(objectMapper).treeToValue(any(JsonNode.class), eq(FIToFICustomerCreditTransfer.class));
        verify(objectMapper, never()).readValue(anyString(), any(Class.class));
        verify(paymentTransactionMapper).fromRegulatoryRequest(any(FIToFICustomerCreditTransfer.class));
        verify(traceRecorder).record("E2E-1", SpiTraceEvent.REQUEST_CONSUMED);
        verify(processor).processTransactionBatch(paymentBatch);
    }

    @Test
    void consumePaymentRequestsMergesKafkaMessagesIntoOneDomainBatch() throws Exception {
        PaymentTransactionMapper paymentTransactionMapper = mock(PaymentTransactionMapper.class);
        StatusReportMapper statusReportMapper = mock(StatusReportMapper.class);
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        ObjectMapper objectMapper = spy(new ObjectMapper());
        PaymentMessageConsumer consumer = new PaymentMessageConsumer(
                paymentTransactionMapper,
                statusReportMapper,
                processor,
                traceRecorder
        );
        ReflectionTestUtils.setField(consumer, "objectMapper", objectMapper);
        PaymentTransaction firstPayment = PaymentTransaction.builder().paymentId("E2E-1").build();
        PaymentTransaction secondPayment = PaymentTransaction.builder().paymentId("E2E-2").build();
        PaymentBatch firstBatch = PaymentBatch.builder()
                .transactions(List.of(firstPayment))
                .build();
        PaymentBatch secondBatch = PaymentBatch.builder()
                .transactions(List.of(secondPayment))
                .build();
        when(paymentTransactionMapper.fromRegulatoryRequest(any(FIToFICustomerCreditTransfer.class)))
                .thenReturn(firstBatch, secondBatch);

        consumer.consumePaymentRequests(List.of(
                """
                        {"CdtTrfTxInf":[]}
                        """.getBytes(StandardCharsets.UTF_8),
                """
                        {"CdtTrfTxInf":[]}
                        """.getBytes(StandardCharsets.UTF_8)
        ));

        verify(objectMapper, times(2)).readTree(any(byte[].class));
        verify(objectMapper, times(2)).treeToValue(any(JsonNode.class), eq(FIToFICustomerCreditTransfer.class));
        verify(paymentTransactionMapper, times(2)).fromRegulatoryRequest(any(FIToFICustomerCreditTransfer.class));
        verify(traceRecorder).record("E2E-1", SpiTraceEvent.REQUEST_CONSUMED);
        verify(traceRecorder).record("E2E-2", SpiTraceEvent.REQUEST_CONSUMED);

        var paymentBatchCaptor = forClass(PaymentBatch.class);
        verify(processor).processTransactionBatch(paymentBatchCaptor.capture());
        assertEquals(List.of("E2E-1", "E2E-2"), paymentBatchCaptor.getValue().getTransactions().stream()
                .map(PaymentTransaction::getPaymentId)
                .toList());
    }

    @Test
    void consumeStatusReportProcessesPacs002FromDedicatedTopic() throws Exception {
        PaymentTransactionMapper paymentTransactionMapper = mock(PaymentTransactionMapper.class);
        StatusReportMapper statusReportMapper = mock(StatusReportMapper.class);
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        ObjectMapper objectMapper = spy(new ObjectMapper());
        PaymentMessageConsumer consumer = new PaymentMessageConsumer(
                paymentTransactionMapper,
                statusReportMapper,
                processor,
                traceRecorder
        );
        ReflectionTestUtils.setField(consumer, "objectMapper", objectMapper);
        StatusBatch statusBatch = StatusBatch.builder()
                .statusReports(List.of(StatusReport.builder().originalPaymentId("E2E-1").build()))
                .build();
        when(statusReportMapper.fromRegulatoryReport(any(FIToFIPaymentStatusReport.class)))
                .thenReturn(statusBatch);

        consumer.consumeStatusReport("""
                {"TxInfAndSts":[]}
                """.getBytes(StandardCharsets.UTF_8));

        verify(objectMapper).readTree(any(byte[].class));
        verify(objectMapper).treeToValue(any(JsonNode.class), eq(FIToFIPaymentStatusReport.class));
        verify(paymentTransactionMapper, never()).fromRegulatoryRequest(any(FIToFICustomerCreditTransfer.class));
        verify(statusReportMapper).fromRegulatoryReport(any(FIToFIPaymentStatusReport.class));
        verify(traceRecorder).record("E2E-1", SpiTraceEvent.STATUS_CONSUMED);
        verify(processor).processStatusBatch(statusBatch);
    }

    @Test
    void consumeStatusReportsProcessesMultipleKafkaMessagesAsOneDomainBatch() throws Exception {
        PaymentTransactionMapper paymentTransactionMapper = mock(PaymentTransactionMapper.class);
        StatusReportMapper statusReportMapper = mock(StatusReportMapper.class);
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        ObjectMapper objectMapper = spy(new ObjectMapper());
        PaymentMessageConsumer consumer = new PaymentMessageConsumer(
                paymentTransactionMapper,
                statusReportMapper,
                processor,
                traceRecorder
        );
        ReflectionTestUtils.setField(consumer, "objectMapper", objectMapper);
        StatusBatch firstBatch = StatusBatch.builder()
                .statusReports(List.of(StatusReport.builder().originalPaymentId("E2E-1").build()))
                .build();
        StatusBatch secondBatch = StatusBatch.builder()
                .statusReports(List.of(StatusReport.builder().originalPaymentId("E2E-2").build()))
                .build();
        when(statusReportMapper.fromRegulatoryReport(any(FIToFIPaymentStatusReport.class)))
                .thenReturn(firstBatch, secondBatch);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        consumer.consumeStatusReports(List.of(
                """
                        {"TxInfAndSts":[]}
                        """.getBytes(StandardCharsets.UTF_8),
                """
                        {"TxInfAndSts":[]}
                        """.getBytes(StandardCharsets.UTF_8)
        ), acknowledgment);

        verify(objectMapper, times(2)).readTree(any(byte[].class));
        verify(objectMapper, times(2)).treeToValue(any(JsonNode.class), eq(FIToFIPaymentStatusReport.class));
        verify(statusReportMapper, times(2)).fromRegulatoryReport(any(FIToFIPaymentStatusReport.class));
        verify(traceRecorder).record("E2E-1", SpiTraceEvent.STATUS_CONSUMED);
        verify(traceRecorder).record("E2E-2", SpiTraceEvent.STATUS_CONSUMED);
        var statusBatchCaptor = forClass(StatusBatch.class);
        verify(processor).processStatusBatch(statusBatchCaptor.capture());
        assertEquals(List.of("E2E-1", "E2E-2"), statusBatchCaptor.getValue().getStatusReports().stream()
                .map(StatusReport::getOriginalPaymentId)
                .toList());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void consumeStatusReportsDoesNotAcknowledgeWhenProcessingFails() throws Exception {
        PaymentTransactionMapper paymentTransactionMapper = mock(PaymentTransactionMapper.class);
        StatusReportMapper statusReportMapper = mock(StatusReportMapper.class);
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        ObjectMapper objectMapper = spy(new ObjectMapper());
        PaymentMessageConsumer consumer = new PaymentMessageConsumer(
                paymentTransactionMapper,
                statusReportMapper,
                processor,
                traceRecorder
        );
        ReflectionTestUtils.setField(consumer, "objectMapper", objectMapper);
        StatusBatch statusBatch = StatusBatch.builder()
                .statusReports(List.of(StatusReport.builder().originalPaymentId("E2E-1").build()))
                .build();
        when(statusReportMapper.fromRegulatoryReport(any(FIToFIPaymentStatusReport.class)))
                .thenReturn(statusBatch);
        doThrow(new RuntimeException("processing failed"))
                .when(processor).processStatusBatch(any(StatusBatch.class));
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        consumer.consumeStatusReports(List.of("""
                {"TxInfAndSts":[]}
                """.getBytes(StandardCharsets.UTF_8)), acknowledgment);

        verify(acknowledgment, never()).acknowledge();
    }
}
