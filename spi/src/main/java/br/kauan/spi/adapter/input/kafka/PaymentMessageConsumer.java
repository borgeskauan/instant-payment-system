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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ObjectReader paymentRequestReader;
    private final ObjectReader statusReportReader;

    @Autowired
    public PaymentMessageConsumer(
            PaymentTransactionMapper paymentTransactionMapper,
            StatusReportMapper statusReportMapper,
            PaymentTransactionProcessorUseCase paymentTransactionProcessorUseCase,
            SpiTraceRecorder traceRecorder
    ) {
        this(paymentTransactionMapper, statusReportMapper, paymentTransactionProcessorUseCase, traceRecorder, createObjectMapper());
    }

    private PaymentMessageConsumer(
            PaymentTransactionMapper paymentTransactionMapper,
            StatusReportMapper statusReportMapper,
            PaymentTransactionProcessorUseCase paymentTransactionProcessorUseCase,
            SpiTraceRecorder traceRecorder,
            ObjectMapper objectMapper
    ) {
        this(paymentTransactionMapper,
                statusReportMapper,
                paymentTransactionProcessorUseCase,
                traceRecorder,
                objectMapper.readerFor(FIToFICustomerCreditTransfer.class),
                objectMapper.readerFor(FIToFIPaymentStatusReport.class));
    }

    PaymentMessageConsumer(
            PaymentTransactionMapper paymentTransactionMapper,
            StatusReportMapper statusReportMapper,
            PaymentTransactionProcessorUseCase paymentTransactionProcessorUseCase,
            SpiTraceRecorder traceRecorder,
            ObjectReader paymentRequestReader,
            ObjectReader statusReportReader
    ) {
        this.paymentTransactionMapper = paymentTransactionMapper;
        this.statusReportMapper = statusReportMapper;
        this.paymentTransactionProcessorUseCase = paymentTransactionProcessorUseCase;
        this.traceRecorder = traceRecorder;
        this.paymentRequestReader = paymentRequestReader;
        this.statusReportReader = statusReportReader;
        log.debug("PaymentMessageConsumer initialized - ready to consume from topics '{}' and '{}'",
                PAYMENT_REQUESTS_TOPIC, PAYMENT_STATUS_REPORTS_TOPIC);
    }

    private static ObjectMapper createObjectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @KafkaListener(
            topics = PAYMENT_REQUESTS_TOPIC,
            groupId = "${spi.kafka.payment-request-group-id:spi-payment-request-consumer-group}",
            containerFactory = "paymentRequestKafkaListenerContainerFactory"
    )
    public void consumePaymentRequests(List<byte[]> payloads) {
        try {
            log.debug("Received batch from Kafka topic '{}', records: {}", PAYMENT_REQUESTS_TOPIC, payloads.size());
            var payments = new ArrayList<PaymentTransaction>(payloads.size());

            for (byte[] payload : payloads) {
                PacsTraceIds.recordPaymentRequestReceived(payload, traceRecorder);
                PaymentBatch paymentBatch = toPaymentBatch(payload);
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
            PacsTraceIds.recordPaymentRequestReceived(payload, traceRecorder);
            processPaymentTransaction(payload);
        } catch (Exception e) {
            log.error("Error processing payment transaction from Kafka", e);
        }
    }

    @KafkaListener(
            topics = PAYMENT_STATUS_REPORTS_TOPIC,
            groupId = "${spi.kafka.status-report-group-id:spi-status-report-consumer-group}",
            containerFactory = "statusReportKafkaListenerContainerFactory"
    )
    public void consumeStatusReports(List<byte[]> payloads, Acknowledgment acknowledgment) {
        try {
            log.debug("Received batch from Kafka topic '{}', records: {}", PAYMENT_STATUS_REPORTS_TOPIC, payloads.size());
            var statusReports = new ArrayList<StatusReport>(payloads.size());

            for (byte[] payload : payloads) {
                PacsTraceIds.recordStatusReportReceived(payload, traceRecorder);
                StatusBatch statusBatch = toStatusBatch(payload);
                statusReports.addAll(statusBatch.getStatusReports());
            }

            for (StatusReport report : statusReports) {
                traceRecorder.record(report.getOriginalPaymentId(), SpiTraceEvent.STATUS_CONSUMED);
            }

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
            PacsTraceIds.recordStatusReportReceived(payload, traceRecorder);
            processStatusReport(payload);
        } catch (Exception e) {
            log.error("Error processing status report from Kafka", e);
        }
    }

    private void processPaymentTransaction(byte[] payload) {
        try {
            PaymentBatch paymentBatch = toPaymentBatch(payload);

            log.debug("Processing payment transaction batch. transactions={}",
                    paymentBatch.getTransactions().size());

            paymentTransactionProcessorUseCase.processTransactionBatch(paymentBatch);

        } catch (Exception e) {
            log.error("Error processing payment transaction from Kafka", e);
            throw new RuntimeException("Failed to process payment transaction", e);
        }
    }

    private PaymentBatch toPaymentBatch(byte[] payload) {
        try {
            FIToFICustomerCreditTransfer request = paymentRequestReader.readValue(payload);

            PaymentBatch paymentBatch = paymentTransactionMapper.fromRegulatoryRequest(request);
            for (PaymentTransaction payment : paymentBatch.getTransactions()) {
                traceRecorder.record(payment.getPaymentId(), SpiTraceEvent.REQUEST_CONSUMED);
            }
            return paymentBatch;
        } catch (Exception e) {
            log.error("Error parsing payment transaction from Kafka", e);
            throw new RuntimeException("Failed to parse payment transaction", e);
        }
    }

    private void processStatusReport(byte[] payload) {
        try {
            StatusBatch statusBatch = toStatusBatch(payload);
            for (StatusReport report : statusBatch.getStatusReports()) {
                traceRecorder.record(report.getOriginalPaymentId(), SpiTraceEvent.STATUS_CONSUMED);
            }

            log.debug("Processing status report batch. reports={}", statusBatch.getStatusReports().size());

            paymentTransactionProcessorUseCase.processStatusBatch(statusBatch);

        } catch (Exception e) {
            log.error("Error processing status report from Kafka", e);
            throw new RuntimeException("Failed to process status report", e);
        }
    }

    private StatusBatch toStatusBatch(byte[] payload) throws java.io.IOException {
        FIToFIPaymentStatusReport statusReport = statusReportReader.readValue(payload);

        return statusReportMapper.fromRegulatoryReport(statusReport);
    }

}
