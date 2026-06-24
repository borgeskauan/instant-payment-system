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
import com.fasterxml.jackson.databind.ObjectReader;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentMessageConsumerTest {

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
    void consumeMessageParsesPaymentTransactionJsonOnlyOnce() throws Exception {
        PaymentTransactionMapper paymentTransactionMapper = mock(PaymentTransactionMapper.class);
        StatusReportMapper statusReportMapper = mock(StatusReportMapper.class);
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        ObjectReader paymentRequestReader = mock(ObjectReader.class);
        ObjectReader statusReportReader = mock(ObjectReader.class);
        PaymentMessageConsumer consumer = new PaymentMessageConsumer(
                paymentTransactionMapper,
                statusReportMapper,
                processor,
                traceRecorder,
                paymentRequestReader,
                statusReportReader
        );
        PaymentBatch paymentBatch = PaymentBatch.builder()
                .transactions(List.of(PaymentTransaction.builder().paymentId("E2E-1").build()))
                .build();
        when(paymentRequestReader.readValue(any(byte[].class)))
                .thenReturn(FIToFICustomerCreditTransfer.builder().build());
        when(paymentTransactionMapper.fromRegulatoryRequest(any(FIToFICustomerCreditTransfer.class)))
                .thenReturn(paymentBatch);

        consumer.consumePaymentRequest("""
                {"CdtTrfTxInf":[]}
                """.getBytes(StandardCharsets.UTF_8));

        verify(paymentRequestReader).readValue(any(byte[].class));
        verify(statusReportReader, never()).readValue(any(byte[].class));
        verify(paymentTransactionMapper).fromRegulatoryRequest(any(FIToFICustomerCreditTransfer.class));
        verify(traceRecorder).record("E2E-1", SpiTraceEvent.REQUEST_CONSUMED);
        verify(processor).processTransactionBatch(paymentBatch);
    }

    @Test
    void consumePaymentRequestRecordsReceivedBeforeParsingPayload() throws Exception {
        PaymentTransactionMapper paymentTransactionMapper = mock(PaymentTransactionMapper.class);
        StatusReportMapper statusReportMapper = mock(StatusReportMapper.class);
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        ObjectReader paymentRequestReader = mock(ObjectReader.class);
        ObjectReader statusReportReader = mock(ObjectReader.class);
        when(traceRecorder.isActive()).thenReturn(true);
        PaymentMessageConsumer consumer = new PaymentMessageConsumer(
                paymentTransactionMapper,
                statusReportMapper,
                processor,
                traceRecorder,
                paymentRequestReader,
                statusReportReader
        );
        PaymentBatch paymentBatch = PaymentBatch.builder()
                .transactions(List.of(PaymentTransaction.builder().paymentId("E2E-1").build()))
                .build();
        when(paymentRequestReader.readValue(any(byte[].class)))
                .thenReturn(FIToFICustomerCreditTransfer.builder().build());
        when(paymentTransactionMapper.fromRegulatoryRequest(any(FIToFICustomerCreditTransfer.class)))
                .thenReturn(paymentBatch);

        byte[] payload = """
                {"CdtTrfTxInf":[{"PmtId":{"EndToEndId":"E2E-1"}}]}
                """.getBytes(StandardCharsets.UTF_8);
        consumer.consumePaymentRequest(payload);

        var inOrder = inOrder(traceRecorder, paymentRequestReader);
        inOrder.verify(traceRecorder).record("E2E-1", SpiTraceEvent.REQUEST_RECEIVED);
        inOrder.verify(paymentRequestReader).readValue(payload);
        inOrder.verify(traceRecorder).record("E2E-1", SpiTraceEvent.REQUEST_CONSUMED);
    }

    @Test
    void consumePaymentRequestsMergesKafkaMessagesIntoOneDomainBatch() throws Exception {
        PaymentTransactionMapper paymentTransactionMapper = mock(PaymentTransactionMapper.class);
        StatusReportMapper statusReportMapper = mock(StatusReportMapper.class);
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        ObjectReader paymentRequestReader = mock(ObjectReader.class);
        ObjectReader statusReportReader = mock(ObjectReader.class);
        PaymentMessageConsumer consumer = new PaymentMessageConsumer(
                paymentTransactionMapper,
                statusReportMapper,
                processor,
                traceRecorder,
                paymentRequestReader,
                statusReportReader
        );
        PaymentTransaction firstPayment = PaymentTransaction.builder().paymentId("E2E-1").build();
        PaymentTransaction secondPayment = PaymentTransaction.builder().paymentId("E2E-2").build();
        PaymentBatch firstBatch = PaymentBatch.builder()
                .transactions(List.of(firstPayment))
                .build();
        PaymentBatch secondBatch = PaymentBatch.builder()
                .transactions(List.of(secondPayment))
                .build();
        when(paymentRequestReader.readValue(any(byte[].class)))
                .thenReturn(FIToFICustomerCreditTransfer.builder().build());
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

        verify(paymentRequestReader, times(2)).readValue(any(byte[].class));
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
    void consumePaymentRequestParsesIsoOffsetTimestampWithDefaultReader() {
        PaymentTransactionMapper paymentTransactionMapper = new PaymentTransactionMapper(
                mock(br.kauan.spi.adapter.input.dtos.pacs.commons.CommonsMapper.class),
                new br.kauan.spi.adapter.input.dtos.pacs.CodeMapping()
        );
        StatusReportMapper statusReportMapper = mock(StatusReportMapper.class);
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        PaymentMessageConsumer consumer = new PaymentMessageConsumer(
                paymentTransactionMapper,
                statusReportMapper,
                processor,
                traceRecorder
        );

        consumer.consumePaymentRequest("""
                {"GrpHdr":{"MsgId":"MSG-1","CreDtTm":"2026-06-23T20:00:01.123Z","NbOfTxs":1},"CdtTrfTxInf":[{"PmtId":{"EndToEndId":"E2E-1"},"IntrBkSttlmAmt":{"value":10.00,"Ccy":"BRL"},"Dbtr":{"Nm":"Sender","Id":{"PrvtId":{"Othr":{"Id":"12345678900"}}}},"DbtrAcct":{"Id":{"Othr":{"Id":987654,"Issr":1234}},"Tp":{"Cd":"CACC"}},"DbtrAgt":{"FinInstnId":{"ClrSysMmbId":{"MmbId":"10000001"}}},"CdtrAgt":{"FinInstnId":{"ClrSysMmbId":{"MmbId":"20000001"}}},"Cdtr":{"Nm":"Receiver","Id":{"PrvtId":{"Othr":{"Id":"98765432100"}}}},"CdtrAcct":{"Id":{"Othr":{"Id":123456,"Issr":5678}},"Tp":{"Cd":"CACC"},"Prxy":{"Id":"+5511999999999"}},"RmtInf":{"Ustrd":"Load test payment"}}]}
                """.getBytes(StandardCharsets.UTF_8));

        var paymentBatchCaptor = forClass(PaymentBatch.class);
        verify(processor).processTransactionBatch(paymentBatchCaptor.capture());
        assertEquals(Instant.parse("2026-06-23T20:00:01.123Z"),
                paymentBatchCaptor.getValue().getBatchDetails().getCreatedAt());
    }

    @Test
    void consumeStatusReportProcessesPacs002FromDedicatedTopic() throws Exception {
        PaymentTransactionMapper paymentTransactionMapper = mock(PaymentTransactionMapper.class);
        StatusReportMapper statusReportMapper = mock(StatusReportMapper.class);
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        ObjectReader paymentRequestReader = mock(ObjectReader.class);
        ObjectReader statusReportReader = mock(ObjectReader.class);
        PaymentMessageConsumer consumer = new PaymentMessageConsumer(
                paymentTransactionMapper,
                statusReportMapper,
                processor,
                traceRecorder,
                paymentRequestReader,
                statusReportReader
        );
        StatusBatch statusBatch = StatusBatch.builder()
                .statusReports(List.of(StatusReport.builder().originalPaymentId("E2E-1").build()))
                .build();
        when(statusReportReader.readValue(any(byte[].class)))
                .thenReturn(FIToFIPaymentStatusReport.builder().build());
        when(statusReportMapper.fromRegulatoryReport(any(FIToFIPaymentStatusReport.class)))
                .thenReturn(statusBatch);

        consumer.consumeStatusReport("""
                {"TxInfAndSts":[]}
                """.getBytes(StandardCharsets.UTF_8));

        verify(statusReportReader).readValue(any(byte[].class));
        verify(paymentRequestReader, never()).readValue(any(byte[].class));
        verify(paymentTransactionMapper, never()).fromRegulatoryRequest(any(FIToFICustomerCreditTransfer.class));
        verify(statusReportMapper).fromRegulatoryReport(any(FIToFIPaymentStatusReport.class));
        verify(traceRecorder).record("E2E-1", SpiTraceEvent.STATUS_CONSUMED);
        verify(processor).processStatusBatch(statusBatch);
    }

    @Test
    void consumeStatusReportRecordsReceivedBeforeParsingPayload() throws Exception {
        PaymentTransactionMapper paymentTransactionMapper = mock(PaymentTransactionMapper.class);
        StatusReportMapper statusReportMapper = mock(StatusReportMapper.class);
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        ObjectReader paymentRequestReader = mock(ObjectReader.class);
        ObjectReader statusReportReader = mock(ObjectReader.class);
        when(traceRecorder.isActive()).thenReturn(true);
        PaymentMessageConsumer consumer = new PaymentMessageConsumer(
                paymentTransactionMapper,
                statusReportMapper,
                processor,
                traceRecorder,
                paymentRequestReader,
                statusReportReader
        );
        StatusBatch statusBatch = StatusBatch.builder()
                .statusReports(List.of(StatusReport.builder().originalPaymentId("E2E-1").build()))
                .build();
        when(statusReportReader.readValue(any(byte[].class)))
                .thenReturn(FIToFIPaymentStatusReport.builder().build());
        when(statusReportMapper.fromRegulatoryReport(any(FIToFIPaymentStatusReport.class)))
                .thenReturn(statusBatch);

        byte[] payload = """
                {"TxInfAndSts":[{"OrgnlEndToEndId":"E2E-1","TxSts":"ACSP"}]}
                """.getBytes(StandardCharsets.UTF_8);
        consumer.consumeStatusReport(payload);

        var inOrder = inOrder(traceRecorder, statusReportReader);
        inOrder.verify(traceRecorder).record("E2E-1", SpiTraceEvent.STATUS_RECEIVED);
        inOrder.verify(statusReportReader).readValue(payload);
        inOrder.verify(traceRecorder).record("E2E-1", SpiTraceEvent.STATUS_CONSUMED);
    }

    @Test
    void consumeStatusReportsProcessesMultipleKafkaMessagesAsOneDomainBatch() throws Exception {
        PaymentTransactionMapper paymentTransactionMapper = mock(PaymentTransactionMapper.class);
        StatusReportMapper statusReportMapper = mock(StatusReportMapper.class);
        PaymentTransactionProcessorUseCase processor = mock(PaymentTransactionProcessorUseCase.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        ObjectReader paymentRequestReader = mock(ObjectReader.class);
        ObjectReader statusReportReader = mock(ObjectReader.class);
        PaymentMessageConsumer consumer = new PaymentMessageConsumer(
                paymentTransactionMapper,
                statusReportMapper,
                processor,
                traceRecorder,
                paymentRequestReader,
                statusReportReader
        );
        StatusBatch firstBatch = StatusBatch.builder()
                .statusReports(List.of(StatusReport.builder().originalPaymentId("E2E-1").build()))
                .build();
        StatusBatch secondBatch = StatusBatch.builder()
                .statusReports(List.of(StatusReport.builder().originalPaymentId("E2E-2").build()))
                .build();
        when(statusReportReader.readValue(any(byte[].class)))
                .thenReturn(FIToFIPaymentStatusReport.builder().build());
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

        verify(statusReportReader, times(2)).readValue(any(byte[].class));
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
        ObjectReader paymentRequestReader = mock(ObjectReader.class);
        ObjectReader statusReportReader = mock(ObjectReader.class);
        PaymentMessageConsumer consumer = new PaymentMessageConsumer(
                paymentTransactionMapper,
                statusReportMapper,
                processor,
                traceRecorder,
                paymentRequestReader,
                statusReportReader
        );
        StatusBatch statusBatch = StatusBatch.builder()
                .statusReports(List.of(StatusReport.builder().originalPaymentId("E2E-1").build()))
                .build();
        when(statusReportReader.readValue(any(byte[].class)))
                .thenReturn(FIToFIPaymentStatusReport.builder().build());
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
