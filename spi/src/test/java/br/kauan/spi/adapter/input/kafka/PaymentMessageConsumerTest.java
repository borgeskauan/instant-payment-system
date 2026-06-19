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
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
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
        verify(processor).processTransactionBatch("00000000", paymentBatch);
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
        verify(processor).processStatusBatch("00000000", statusBatch);
    }
}
