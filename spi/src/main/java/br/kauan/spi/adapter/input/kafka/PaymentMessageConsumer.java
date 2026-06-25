package br.kauan.spi.adapter.input.kafka;

import br.kauan.pix.internal.v1.PaymentRequest;
import br.kauan.pix.internal.v1.PaymentStatusReport;
import br.kauan.spi.adapter.input.kafka.internal.InternalPaymentMessageMapper;
import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.domain.services.tracing.SpiTraceEvent;
import br.kauan.spi.domain.services.tracing.SpiTraceRecorder;
import br.kauan.spi.port.input.PaymentTransactionProcessorUseCase;
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

    private final InternalPaymentMessageMapper messageMapper;
    private final PaymentTransactionProcessorUseCase paymentTransactionProcessorUseCase;
    private final SpiTraceRecorder traceRecorder;

    @Autowired
    public PaymentMessageConsumer(
            InternalPaymentMessageMapper messageMapper,
            PaymentTransactionProcessorUseCase paymentTransactionProcessorUseCase,
            SpiTraceRecorder traceRecorder
    ) {
        this.messageMapper = messageMapper;
        this.paymentTransactionProcessorUseCase = paymentTransactionProcessorUseCase;
        this.traceRecorder = traceRecorder;
        log.debug("PaymentMessageConsumer initialized - ready to consume from topics '{}' and '{}'",
                PAYMENT_REQUESTS_TOPIC, PAYMENT_STATUS_REPORTS_TOPIC);
    }

    @KafkaListener(
            topics = PAYMENT_REQUESTS_TOPIC,
            groupId = "${spi.kafka.payment-request-group-id:spi-payment-request-consumer-group}",
            containerFactory = "paymentRequestKafkaListenerContainerFactory"
    )
    public void consumePaymentRequests(List<byte[]> payloads) {
        try {
            log.debug("Received records from Kafka topic '{}', records: {}", PAYMENT_REQUESTS_TOPIC, payloads.size());
            var payments = new ArrayList<PaymentTransactionCommand>(payloads.size());

            for (byte[] payload : payloads) {
                PaymentTransactionCommand payment = toPaymentTransaction(payload);
                payments.add(payment);
            }

            if (payments.isEmpty()) {
                return;
            }

            paymentTransactionProcessorUseCase.processTransactions(payments);
        } catch (Exception e) {
            log.error("Error processing payment transaction records from Kafka", e);
        }
    }

    @KafkaListener(
            topics = PAYMENT_STATUS_REPORTS_TOPIC,
            groupId = "${spi.kafka.status-report-group-id:spi-status-report-consumer-group}",
            containerFactory = "statusReportKafkaListenerContainerFactory"
    )
    public void consumeStatusReports(List<byte[]> payloads, Acknowledgment acknowledgment) {
        try {
            log.debug("Received records from Kafka topic '{}', records: {}", PAYMENT_STATUS_REPORTS_TOPIC, payloads.size());
            var statusReports = new ArrayList<StatusReportCommand>(payloads.size());

            for (byte[] payload : payloads) {
                statusReports.add(toStatusReport(payload));
            }

            log.debug("Processing status reports. reports={}", statusReports.size());
            paymentTransactionProcessorUseCase.processStatusReports(statusReports);
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("Error processing status report records from Kafka", e);
        }
    }

    public void consumeStatusReports(List<byte[]> payloads) {
        consumeStatusReports(payloads, () -> {
        });
    }

    private PaymentTransactionCommand toPaymentTransaction(byte[] payload) {
        try {
            PaymentRequest request = PaymentRequest.parseFrom(payload);
            traceRecorder.record(request.getPaymentId(), SpiTraceEvent.REQUEST_CONSUMED);
            return messageMapper.toPaymentTransaction(request);
        } catch (Exception e) {
            log.error("Error parsing payment transaction from Kafka", e);
            throw new RuntimeException("Failed to parse payment transaction", e);
        }
    }

    private StatusReportCommand toStatusReport(byte[] payload) {
        try {
            PaymentStatusReport report = PaymentStatusReport.parseFrom(payload);
            traceRecorder.record(report.getPaymentId(), SpiTraceEvent.STATUS_RECEIVED);
            return messageMapper.toStatusReport(report);
        } catch (Exception e) {
            log.error("Error parsing status report from Kafka", e);
            throw new RuntimeException("Failed to parse status report", e);
        }
    }

}
