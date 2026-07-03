package br.kauan.spi.adapter.input.kafka.consumer;

import br.kauan.spi.adapter.input.kafka.infrastructure.dlq.InvalidPayloadDlqPublisher;
import br.kauan.spi.adapter.input.kafka.infrastructure.error.InfrastructureUnavailableException;
import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.port.input.PaymentTransactionProcessorUseCase;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
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

    private final InboundPaymentMessageDecoder messageDecoder;
    private final PaymentTransactionProcessorUseCase paymentTransactionProcessorUseCase;
    private final InvalidPayloadDlqPublisher invalidPayloadDlqPublisher;

    @Autowired
    public PaymentMessageConsumer(
            InboundPaymentMessageDecoder messageDecoder,
            PaymentTransactionProcessorUseCase paymentTransactionProcessorUseCase,
            InvalidPayloadDlqPublisher invalidPayloadDlqPublisher
    ) {
        this.messageDecoder = messageDecoder;
        this.paymentTransactionProcessorUseCase = paymentTransactionProcessorUseCase;
        this.invalidPayloadDlqPublisher = invalidPayloadDlqPublisher;
        log.debug("PaymentMessageConsumer initialized - ready to consume from topics '{}' and '{}'",
                PAYMENT_REQUESTS_TOPIC, PAYMENT_STATUS_REPORTS_TOPIC);
    }

    @KafkaListener(
            topics = PAYMENT_REQUESTS_TOPIC,
            groupId = "${spi.kafka.payment-request-group-id:spi-payment-request-consumer-group}",
            containerFactory = "spiKafkaListenerContainerFactory"
    )
    public void consumePaymentRequests(
            List<ConsumerRecord<String, byte[]>> records,
            Acknowledgment acknowledgment
    ) {
        log.debug("Received records from Kafka topic '{}', records: {}", PAYMENT_REQUESTS_TOPIC, records.size());
        var payments = new ArrayList<PaymentTransactionCommand>(records.size());

        for (ConsumerRecord<String, byte[]> record : records) {
            try {
                PaymentTransactionCommand payment = messageDecoder.toPaymentTransaction(record);
                payments.add(payment);
            } catch (InvalidInboundPayloadException e) {
                invalidPayloadDlqPublisher.publish(record, e);
            }
        }

        if (!payments.isEmpty()) {
            try {
                paymentTransactionProcessorUseCase.processTransactions(payments);
            } catch (DataAccessResourceFailureException e) {
                throw databaseUnavailable(
                        PAYMENT_REQUESTS_TOPIC,
                        payments.size(),
                        e);
            }
        }

        acknowledgment.acknowledge();
    }

    @KafkaListener(
            topics = PAYMENT_STATUS_REPORTS_TOPIC,
            groupId = "${spi.kafka.status-report-group-id:spi-status-report-consumer-group}",
            containerFactory = "spiKafkaListenerContainerFactory"
    )
    public void consumeStatusReports(
            List<ConsumerRecord<String, byte[]>> records,
            Acknowledgment acknowledgment
    ) {
        log.debug("Received records from Kafka topic '{}', records: {}", PAYMENT_STATUS_REPORTS_TOPIC, records.size());
        var statusReports = new ArrayList<StatusReportCommand>(records.size());

        for (ConsumerRecord<String, byte[]> record : records) {
            try {
                StatusReportCommand statusReport = messageDecoder.toStatusReport(record);
                log.debug("Processing status report. payment_id={}", statusReport.getOriginalPaymentId());
                statusReports.add(statusReport);
            } catch (InvalidInboundPayloadException e) {
                invalidPayloadDlqPublisher.publish(record, e);
            }
        }

        if (!statusReports.isEmpty()) {
            try {
                paymentTransactionProcessorUseCase.processStatusReports(statusReports);
            } catch (DataAccessResourceFailureException e) {
                throw databaseUnavailable(
                        PAYMENT_STATUS_REPORTS_TOPIC,
                        statusReports.size(),
                        e);
            }
        }

        acknowledgment.acknowledge();
    }

    private InfrastructureUnavailableException databaseUnavailable(
            String topic,
            int records,
            DataAccessResourceFailureException exception
    ) {
        KafkaConsumerLogs.infrastructureUnavailable(topic, records, exception);
        return new InfrastructureUnavailableException(
                "Database unavailable while processing SPI batch",
                exception);
    }
}
