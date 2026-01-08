package br.kauan.spi.domain.services.notification;

import br.kauan.spi.adapter.input.dtos.pacs.PaymentTransactionMapper;
import br.kauan.spi.adapter.input.dtos.pacs.StatusReportMapper;
import br.kauan.spi.adapter.input.dtos.pacs.pacs002.FIToFIPaymentStatusReport;
import br.kauan.spi.adapter.input.dtos.pacs.pacs008.FIToFICustomerCreditTransfer;
import br.kauan.spi.adapter.output.kafka.NotificationPublisher;
import br.kauan.spi.domain.entity.commons.BatchDetails;
import br.kauan.spi.domain.entity.status.StatusBatch;
import br.kauan.spi.domain.entity.status.StatusReport;
import br.kauan.spi.domain.entity.transfer.PaymentBatch;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class NotificationStorage {

    private final StatusReportMapper statusReportMapper;
    private final PaymentTransactionMapper paymentTransactionMapper;
    private final NotificationContentSerializer contentSerializer;
    private final NotificationPublisher notificationPublisher;

    public NotificationStorage(
            StatusReportMapper statusReportMapper,
            PaymentTransactionMapper paymentTransactionMapper,
            NotificationPublisher notificationPublisher
    ) {
        this.statusReportMapper = statusReportMapper;
        this.paymentTransactionMapper = paymentTransactionMapper;
        this.notificationPublisher = notificationPublisher;
        this.contentSerializer = new NotificationContentSerializer();
    }

    /**
     * Publishes a status notification directly to Kafka.
     * No longer stores in-memory - immediately publishes to notifications-topic.
     */
    public void addStatusNotification(String ispb, StatusReport statusReport) {
        log.debug("Publishing status notification for ISPB: {}", ispb);
        
        FIToFIPaymentStatusReport statusBatchNotification = 
                createStatusBatchNotification(Collections.singletonList(statusReport));
        
        if (statusBatchNotification != null) {
            contentSerializer.serialize(statusBatchNotification)
                    .ifPresent(json -> notificationPublisher.publishNotification(ispb, json));
        }
    }

    /**
     * Publishes a transaction notification directly to Kafka.
     * No longer stores in-memory - immediately publishes to notifications-topic.
     */
    public void addTransactionNotification(String ispb, PaymentTransaction paymentTransaction) {
        log.debug("Publishing transaction notification for ISPB: {}", ispb);
        
        FIToFICustomerCreditTransfer paymentBatchNotification = 
                createPaymentBatchNotification(Collections.singletonList(paymentTransaction));
        
        if (paymentBatchNotification != null) {
            contentSerializer.serialize(paymentBatchNotification)
                    .ifPresent(json -> notificationPublisher.publishNotification(ispb, json));
        }
    }

    private FIToFIPaymentStatusReport createStatusBatchNotification(List<StatusReport> statusReports) {
        if (statusReports.isEmpty()) {
            return null;
        }

        BatchDetails batchDetails = BatchDetails.of(statusReports.size());
        StatusBatch statusBatch = StatusBatch.builder()
                .batchDetails(batchDetails)
                .statusReports(statusReports)
                .build();

        return statusReportMapper.toRegulatoryReport(statusBatch);
    }

    private FIToFICustomerCreditTransfer createPaymentBatchNotification(List<PaymentTransaction> transactions) {
        if (transactions.isEmpty()) {
            return null;
        }

        BatchDetails batchDetails = BatchDetails.of(transactions.size());
        PaymentBatch paymentBatch = PaymentBatch.builder()
                .batchDetails(batchDetails)
                .transactions(transactions)
                .build();

        return paymentTransactionMapper.toRegulatoryRequest(paymentBatch);
    }
}