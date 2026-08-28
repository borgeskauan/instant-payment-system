package br.kauan.spi.adapter.input.kafka.consumer;

import br.kauan.spi.Utils;
import br.kauan.spi.adapter.input.kafka.infrastructure.dlq.DlqPublisher;
import br.kauan.spi.adapter.input.kafka.infrastructure.error.InfrastructureUnavailableException;
import br.kauan.spi.domain.entity.security.AuthenticatedPaymentRequest;
import br.kauan.spi.domain.entity.security.AuthenticatedStatusReport;
import br.kauan.spi.port.input.PaymentTransactionProcessorUseCase;
import br.kauan.spi.port.input.StatusReportProcessingResult;
import br.kauan.spi.port.output.PaymentTransactionPersistenceResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
@Component
public class PaymentMessageConsumer {

    private static final String PAYMENT_REQUESTS_TOPIC = "spi-payment-requests";
    private static final String PAYMENT_STATUS_REPORTS_TOPIC = "spi-payment-status-reports";

    private final InboundPaymentMessageDecoder messageDecoder;
    private final PaymentTransactionProcessorUseCase paymentTransactionProcessorUseCase;
    private final DlqPublisher dlqPublisher;

    public PaymentMessageConsumer(
            InboundPaymentMessageDecoder messageDecoder,
            PaymentTransactionProcessorUseCase paymentTransactionProcessorUseCase,
            DlqPublisher dlqPublisher
    ) {
        this.messageDecoder = messageDecoder;
        this.paymentTransactionProcessorUseCase = paymentTransactionProcessorUseCase;
        this.dlqPublisher = dlqPublisher;
        log.debug("PaymentMessageConsumer initialized - ready to consume from topics '{}' and '{}'",
                PAYMENT_REQUESTS_TOPIC, PAYMENT_STATUS_REPORTS_TOPIC);
    }

    @KafkaListener(
            topics = PAYMENT_REQUESTS_TOPIC,
            groupId = "${spi.kafka.payment-request-group-id}",
            containerFactory = "paymentRequestKafkaListenerContainerFactory"
    )
    public void consumePaymentRequests(
            List<ConsumerRecord<String, byte[]>> records,
            Acknowledgment acknowledgment
    ) {
        try (var ignored = KafkaBatchReceivedEvent.start(PAYMENT_REQUESTS_TOPIC, records.size())) {
            processPaymentRequestBatch(records, acknowledgment);
        }
    }

    private void processPaymentRequestBatch(
            List<ConsumerRecord<String, byte[]>> records,
            Acknowledgment acknowledgment
    ) {
        log.debug("Received records from Kafka topic '{}', records: {}", PAYMENT_REQUESTS_TOPIC, records.size());
        var payments = new ArrayList<AuthenticatedPaymentRequest>(records.size());

        for (int sourceOrdinal = 0; sourceOrdinal < records.size(); sourceOrdinal++) {
            ConsumerRecord<String, byte[]> record = records.get(sourceOrdinal);
            try {
                String authenticatedIspb = AuthenticatedIspbHeaderExtractor.extract(record);
                var payment = messageDecoder.toPaymentTransaction(record);
                if (!Objects.equals(authenticatedIspb, Utils.getBankCode(payment.getSender()))) {
                    dlqPublisher.publish(
                            record,
                            new UnauthorizedPspException(payment.getPaymentId(), authenticatedIspb)
                    );
                    continue;
                }
                payments.add(new AuthenticatedPaymentRequest(sourceOrdinal, authenticatedIspb, payment));
            } catch (NotAuthenticatedException e) {
                dlqPublisher.publish(record, e);
            } catch (InvalidInboundPayloadException e) {
                dlqPublisher.publish(record, e);
            }
        }

        if (!payments.isEmpty()) {
            try {
                PaymentTransactionPersistenceResult result =
                        paymentTransactionProcessorUseCase.processTransactions(payments);
                publishDivergentDuplicates(result, records);
                publishUnauthorizedPaymentRequests(result, records);
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
            List<ConsumerRecord<String, byte[]>> records
    ) {
        for (AuthenticatedPaymentRequest divergentDuplicate : result.divergentDuplicates()) {
            dlqPublisher.publish(
                    recordAt(records, divergentDuplicate.sourceOrdinal()),
                    new DivergentDuplicatePaymentException(divergentDuplicate.command().getPaymentId())
            );
        }
    }

    private void publishUnauthorizedPaymentRequests(
            PaymentTransactionPersistenceResult result,
            List<ConsumerRecord<String, byte[]>> records
    ) {
        for (AuthenticatedPaymentRequest unauthorizedRequest : result.unauthorizedRequests()) {
            dlqPublisher.publish(
                    recordAt(records, unauthorizedRequest.sourceOrdinal()),
                    new UnauthorizedPspException(
                            unauthorizedRequest.command().getPaymentId(),
                            unauthorizedRequest.authenticatedIspb()
                    )
            );
        }
    }

    @KafkaListener(
            topics = PAYMENT_STATUS_REPORTS_TOPIC,
            groupId = "${spi.kafka.status-report-group-id}",
            containerFactory = "statusReportKafkaListenerContainerFactory"
    )
    public void consumeStatusReports(
            List<ConsumerRecord<String, byte[]>> records,
            Acknowledgment acknowledgment
    ) {
        try (var ignored = KafkaBatchReceivedEvent.start(PAYMENT_STATUS_REPORTS_TOPIC, records.size())) {
            processStatusReportBatch(records, acknowledgment);
        }
    }

    private void processStatusReportBatch(
            List<ConsumerRecord<String, byte[]>> records,
            Acknowledgment acknowledgment
    ) {
        log.debug("Received records from Kafka topic '{}', records: {}", PAYMENT_STATUS_REPORTS_TOPIC, records.size());
        var statusReports = new ArrayList<AuthenticatedStatusReport>(records.size());

        for (int sourceOrdinal = 0; sourceOrdinal < records.size(); sourceOrdinal++) {
            ConsumerRecord<String, byte[]> record = records.get(sourceOrdinal);
            try {
                String authenticatedIspb = AuthenticatedIspbHeaderExtractor.extract(record);
                var statusReport = messageDecoder.toStatusReport(record);
                log.debug("Processing status report. payment_id={}", statusReport.originalPaymentId());
                statusReports.add(new AuthenticatedStatusReport(
                        sourceOrdinal,
                        authenticatedIspb,
                        statusReport
                ));
            } catch (NotAuthenticatedException e) {
                dlqPublisher.publish(record, e);
            } catch (InvalidInboundPayloadException e) {
                dlqPublisher.publish(record, e);
            }
        }

        if (!statusReports.isEmpty()) {
            try {
                StatusReportProcessingResult result =
                        paymentTransactionProcessorUseCase.processStatusReports(statusReports);
                publishDivergentStatusReports(result, records);
                publishUnauthorizedStatusReports(result, records);
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
            List<ConsumerRecord<String, byte[]>> records
    ) {
        for (AuthenticatedStatusReport divergentStatusReport : result.divergentStatusReports()) {
            dlqPublisher.publish(
                    recordAt(records, divergentStatusReport.sourceOrdinal()),
                    new DivergentStatusReportException(
                            divergentStatusReport.command().originalPaymentId()
                    )
            );
        }
    }

    private void publishUnauthorizedStatusReports(
            StatusReportProcessingResult result,
            List<ConsumerRecord<String, byte[]>> records
    ) {
        for (AuthenticatedStatusReport unauthorizedStatusReport : result.unauthorizedStatusReports()) {
            dlqPublisher.publish(
                    recordAt(records, unauthorizedStatusReport.sourceOrdinal()),
                    new UnauthorizedPspException(
                            unauthorizedStatusReport.command().originalPaymentId(),
                            unauthorizedStatusReport.authenticatedIspb()
                    )
            );
        }
    }

    private ConsumerRecord<String, byte[]> recordAt(
            List<ConsumerRecord<String, byte[]>> records,
            int sourceOrdinal
    ) {
        if (sourceOrdinal < 0 || sourceOrdinal >= records.size()) {
            throw new IllegalStateException("Invalid source record ordinal: " + sourceOrdinal);
        }
        return records.get(sourceOrdinal);
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
