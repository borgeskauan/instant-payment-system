package br.kauan.spi.adapter.input.kafka.consumer;

import br.kauan.spi.adapter.input.kafka.infrastructure.dlq.InvalidPayloadDlqPublisher;
import br.kauan.spi.adapter.input.kafka.infrastructure.dlq.DivergentDuplicateDlqPublisher;
import br.kauan.spi.adapter.input.kafka.infrastructure.dlq.DivergentStatusReportDlqPublisher;
import br.kauan.spi.adapter.input.kafka.infrastructure.error.InfrastructureUnavailableException;
import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.port.input.PaymentTransactionProcessorUseCase;
import br.kauan.spi.port.input.StatusReportProcessingResult;
import br.kauan.spi.port.output.PaymentTransactionPersistenceResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class PaymentMessageConsumer {

    private static final String PAYMENT_REQUESTS_TOPIC = "spi-payment-requests";
    private static final String PAYMENT_STATUS_REPORTS_TOPIC = "spi-payment-status-reports";

    private final InboundPaymentMessageDecoder messageDecoder;
    private final PaymentTransactionProcessorUseCase paymentTransactionProcessorUseCase;
    private final InvalidPayloadDlqPublisher invalidPayloadDlqPublisher;
    private final DivergentDuplicateDlqPublisher divergentDuplicateDlqPublisher;
    private final DivergentStatusReportDlqPublisher divergentStatusReportDlqPublisher;

    @Autowired
    public PaymentMessageConsumer(
            InboundPaymentMessageDecoder messageDecoder,
            PaymentTransactionProcessorUseCase paymentTransactionProcessorUseCase,
            InvalidPayloadDlqPublisher invalidPayloadDlqPublisher,
            DivergentDuplicateDlqPublisher divergentDuplicateDlqPublisher,
            DivergentStatusReportDlqPublisher divergentStatusReportDlqPublisher
    ) {
        this.messageDecoder = messageDecoder;
        this.paymentTransactionProcessorUseCase = paymentTransactionProcessorUseCase;
        this.invalidPayloadDlqPublisher = invalidPayloadDlqPublisher;
        this.divergentDuplicateDlqPublisher = divergentDuplicateDlqPublisher;
        this.divergentStatusReportDlqPublisher = divergentStatusReportDlqPublisher;
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
        Map<String, List<ConsumerRecord<String, byte[]>>> recordsByPaymentId = new LinkedHashMap<>();

        for (ConsumerRecord<String, byte[]> record : records) {
            try {
                PaymentTransactionCommand payment = messageDecoder.toPaymentTransaction(record);
                payments.add(payment);
                recordsByPaymentId.computeIfAbsent(payment.getPaymentId(), ignored -> new ArrayList<>())
                        .add(record);
            } catch (InvalidInboundPayloadException e) {
                invalidPayloadDlqPublisher.publish(record, e);
            }
        }

        if (!payments.isEmpty()) {
            try {
                PaymentTransactionPersistenceResult result =
                        paymentTransactionProcessorUseCase.processTransactions(payments);
                publishDivergentDuplicates(result, recordsByPaymentId);
            } catch (DataAccessResourceFailureException e) {
                throw databaseUnavailable(
                        PAYMENT_REQUESTS_TOPIC,
                        payments.size(),
                        e);
            }
        }

        acknowledgment.acknowledge();
    }

    private void publishDivergentDuplicates(
            PaymentTransactionPersistenceResult result,
            Map<String, List<ConsumerRecord<String, byte[]>>> recordsByPaymentId
    ) {
        Set<String> divergentPaymentIds = new LinkedHashSet<>();
        for (PaymentTransactionCommand divergentDuplicate : result.divergentDuplicates()) {
            divergentPaymentIds.add(divergentDuplicate.getPaymentId());
        }

        for (String paymentId : divergentPaymentIds) {
            List<ConsumerRecord<String, byte[]>> divergentRecords =
                    recordsByPaymentId.getOrDefault(paymentId, List.of());
            for (ConsumerRecord<String, byte[]> divergentRecord : divergentRecords) {
                divergentDuplicateDlqPublisher.publish(
                        divergentRecord,
                        new DivergentDuplicatePaymentException(paymentId));
            }
        }
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
        Map<String, List<ConsumerRecord<String, byte[]>>> recordsByPaymentId = new LinkedHashMap<>();

        for (ConsumerRecord<String, byte[]> record : records) {
            try {
                StatusReportCommand statusReport = messageDecoder.toStatusReport(record);
                log.debug("Processing status report. payment_id={}", statusReport.getOriginalPaymentId());
                statusReports.add(statusReport);
                recordsByPaymentId.computeIfAbsent(statusReport.getOriginalPaymentId(), ignored -> new ArrayList<>())
                        .add(record);
            } catch (InvalidInboundPayloadException e) {
                invalidPayloadDlqPublisher.publish(record, e);
            }
        }

        if (!statusReports.isEmpty()) {
            try {
                StatusReportProcessingResult result =
                        paymentTransactionProcessorUseCase.processStatusReports(statusReports);
                publishDivergentStatusReports(result, recordsByPaymentId);
            } catch (DataAccessResourceFailureException e) {
                throw databaseUnavailable(
                        PAYMENT_STATUS_REPORTS_TOPIC,
                        statusReports.size(),
                        e);
            }
        }

        acknowledgment.acknowledge();
    }

    private void publishDivergentStatusReports(
            StatusReportProcessingResult result,
            Map<String, List<ConsumerRecord<String, byte[]>>> recordsByPaymentId
    ) {
        Set<String> divergentPaymentIds = new LinkedHashSet<>();
        for (StatusReportCommand divergentStatusReport : result.divergentStatusReports()) {
            divergentPaymentIds.add(divergentStatusReport.getOriginalPaymentId());
        }

        for (String paymentId : divergentPaymentIds) {
            List<ConsumerRecord<String, byte[]>> divergentRecords =
                    recordsByPaymentId.getOrDefault(paymentId, List.of());
            for (ConsumerRecord<String, byte[]> divergentRecord : divergentRecords) {
                divergentStatusReportDlqPublisher.publish(
                        divergentRecord,
                        new DivergentStatusReportException(paymentId));
            }
        }
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
