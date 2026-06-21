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
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class PaymentMessageConsumer {

    private static final String PAYMENT_REQUESTS_TOPIC = "spi-payment-requests";
    private static final String PAYMENT_STATUS_REPORTS_TOPIC = "spi-payment-status-reports";

    private final PaymentTransactionMapper paymentTransactionMapper;
    private final StatusReportMapper statusReportMapper;
    private final PaymentTransactionProcessorUseCase paymentTransactionProcessorUseCase;
    private final SpiTraceRecorder traceRecorder;
    private final ObjectMapper objectMapper;

    public PaymentMessageConsumer(
            PaymentTransactionMapper paymentTransactionMapper,
            StatusReportMapper statusReportMapper,
            PaymentTransactionProcessorUseCase paymentTransactionProcessorUseCase,
            SpiTraceRecorder traceRecorder
    ) {
        this.paymentTransactionMapper = paymentTransactionMapper;
        this.statusReportMapper = statusReportMapper;
        this.paymentTransactionProcessorUseCase = paymentTransactionProcessorUseCase;
        this.traceRecorder = traceRecorder;
        this.objectMapper = new ObjectMapper();
        log.debug("PaymentMessageConsumer initialized - ready to consume from topics '{}' and '{}'",
                PAYMENT_REQUESTS_TOPIC, PAYMENT_STATUS_REPORTS_TOPIC);
    }

    @KafkaListener(
            topics = PAYMENT_REQUESTS_TOPIC,
            groupId = "spi-consumer-group",
            containerFactory = "paymentRequestKafkaListenerContainerFactory"
    )
    public void consumePaymentRequests(List<byte[]> payloads) {
        try {
            log.debug("Received batch from Kafka topic '{}', records: {}", PAYMENT_REQUESTS_TOPIC, payloads.size());
            var payments = new ArrayList<PaymentTransaction>();

            for (byte[] payload : payloads) {
                JsonNode jsonNode = objectMapper.readTree(payload);
                PaymentBatch paymentBatch = toPaymentBatch(jsonNode);
                payments.addAll(paymentBatch.getTransactions());
            }

            if (payments.isEmpty()) {
                return;
            }

            paymentTransactionProcessorUseCase.processTransactionBatch(PaymentBatch.builder()
                    .transactions(payments)
                    .build());
        } catch (Exception e) {
            log.error("Error processing payment transaction batch from Kafka", e);
        }
    }

    public void consumePaymentRequest(byte[] payload) {
        try {
            log.debug("Received message from Kafka topic '{}', size: {} bytes", PAYMENT_REQUESTS_TOPIC, payload.length);
            var jsonNode = objectMapper.readTree(payload);
            processPaymentTransaction(jsonNode);
        } catch (Exception e) {
            log.error("Error processing payment transaction from Kafka", e);
        }
    }

    @KafkaListener(
            topics = PAYMENT_STATUS_REPORTS_TOPIC,
            groupId = "spi-consumer-group",
            containerFactory = "statusReportKafkaListenerContainerFactory"
    )
    public void consumeStatusReports(List<byte[]> payloads, Acknowledgment acknowledgment) {
        try {
            log.debug("Received batch from Kafka topic '{}', records: {}", PAYMENT_STATUS_REPORTS_TOPIC, payloads.size());
            var statusReports = new ArrayList<StatusReport>();

            for (byte[] payload : payloads) {
                JsonNode jsonNode = objectMapper.readTree(payload);
                StatusBatch statusBatch = toStatusBatch(jsonNode);
                statusReports.addAll(statusBatch.getStatusReports());
            }

            statusReports.forEach(report ->
                    traceRecorder.record(report.getOriginalPaymentId(), SpiTraceEvent.STATUS_CONSUMED));

            log.debug("Processing status report batch. reports={}", statusReports.size());
            paymentTransactionProcessorUseCase.processStatusBatch(StatusBatch.builder()
                    .statusReports(statusReports)
                    .build());
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("Error processing status report batch from Kafka", e);
        }
    }

    public void consumeStatusReports(List<byte[]> payloads) {
        consumeStatusReports(payloads, () -> {
        });
    }

    public void consumeStatusReport(byte[] payload) {
        try {
            log.debug("Received message from Kafka topic '{}', size: {} bytes", PAYMENT_STATUS_REPORTS_TOPIC, payload.length);
            var jsonNode = objectMapper.readTree(payload);
            processStatusReport(jsonNode);
        } catch (Exception e) {
            log.error("Error processing status report from Kafka", e);
        }
    }

    private void processPaymentTransaction(JsonNode jsonNode) {
        try {
            PaymentBatch paymentBatch = toPaymentBatch(jsonNode);

            log.debug("Processing payment transaction batch. transactions={}",
                    paymentBatch.getTransactions().size());

            paymentTransactionProcessorUseCase.processTransactionBatch(paymentBatch);

        } catch (Exception e) {
            log.error("Error processing payment transaction from Kafka", e);
            throw new RuntimeException("Failed to process payment transaction", e);
        }
    }

    private PaymentBatch toPaymentBatch(JsonNode jsonNode) {
        try {
            FIToFICustomerCreditTransfer request = objectMapper.treeToValue(
                    jsonNode,
                    FIToFICustomerCreditTransfer.class
            );

            PaymentBatch paymentBatch = paymentTransactionMapper.fromRegulatoryRequest(request);
            paymentBatch.getTransactions().forEach(payment ->
                    traceRecorder.record(payment.getPaymentId(), SpiTraceEvent.REQUEST_CONSUMED));
            return paymentBatch;
        } catch (Exception e) {
            log.error("Error parsing payment transaction from Kafka", e);
            throw new RuntimeException("Failed to parse payment transaction", e);
        }
    }

    private void processStatusReport(JsonNode jsonNode) {
        try {
            StatusBatch statusBatch = toStatusBatch(jsonNode);
            statusBatch.getStatusReports().forEach(report ->
                    traceRecorder.record(report.getOriginalPaymentId(), SpiTraceEvent.STATUS_CONSUMED));

            log.debug("Processing status report batch. reports={}", statusBatch.getStatusReports().size());

            paymentTransactionProcessorUseCase.processStatusBatch(statusBatch);

        } catch (Exception e) {
            log.error("Error processing status report from Kafka", e);
            throw new RuntimeException("Failed to process status report", e);
        }
    }

    private StatusBatch toStatusBatch(JsonNode jsonNode) throws com.fasterxml.jackson.core.JsonProcessingException {
        FIToFIPaymentStatusReport statusReport = objectMapper.treeToValue(
                jsonNode,
                FIToFIPaymentStatusReport.class
        );

        return statusReportMapper.fromRegulatoryReport(statusReport);
    }

}
