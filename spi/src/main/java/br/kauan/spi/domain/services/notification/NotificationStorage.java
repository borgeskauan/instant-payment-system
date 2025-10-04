package br.kauan.spi.domain.services.notification;

import br.kauan.spi.adapter.input.dtos.pacs.PaymentTransactionMapper;
import br.kauan.spi.adapter.input.dtos.pacs.StatusReportMapper;
import br.kauan.spi.adapter.input.dtos.pacs.pacs002.FIToFIPaymentStatusReport;
import br.kauan.spi.adapter.input.dtos.pacs.pacs008.FIToFICustomerCreditTransfer;
import br.kauan.spi.domain.entity.commons.BatchDetails;
import br.kauan.spi.domain.entity.status.StatusBatch;
import br.kauan.spi.domain.entity.status.StatusReport;
import br.kauan.spi.domain.entity.transfer.PaymentBatch;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.domain.services.notification.dto.InstitutionMessages;
import br.kauan.spi.domain.services.notification.dto.SpiNotification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class NotificationStorage {

    private final Map<String, InstitutionMessages> notifications = new ConcurrentHashMap<>();
    private final StatusReportMapper statusReportMapper;
    private final PaymentTransactionMapper paymentTransactionMapper;
    private final NotificationContentSerializer contentSerializer;

    public NotificationStorage(
            StatusReportMapper statusReportMapper,
            PaymentTransactionMapper paymentTransactionMapper
    ) {
        this.statusReportMapper = statusReportMapper;
        this.paymentTransactionMapper = paymentTransactionMapper;
        this.contentSerializer = new NotificationContentSerializer();
    }

    public void addStatusNotification(String ispb, StatusReport statusReport) {
        getMessageContainer(ispb).statuses().add(statusReport);
    }

    public void addTransactionNotification(String ispb, PaymentTransaction paymentTransaction) {
        getMessageContainer(ispb).transactions().add(paymentTransaction);
    }

    public SpiNotification retrieveNotifications(String ispb) {
        InstitutionMessages messages = notifications.remove(ispb);

        if (messages == null || messages.isEmpty()) {
            log.debug("No notifications found for ISPB: {}", ispb);
            return SpiNotification.empty();
        }

        log.debug("Retrieved {} status reports and {} transactions for ISPB: {}",
                messages.statuses().size(), messages.transactions().size(), ispb);
        return convertToSpiNotifications(messages);
    }

    private SpiNotification convertToSpiNotifications(InstitutionMessages messages) {
        List<String> content = new ArrayList<>();

        Optional.ofNullable(createStatusBatchNotification(messages.statuses()))
                .flatMap(contentSerializer::serialize)
                .ifPresent(content::add);

        Optional.ofNullable(createPaymentBatchNotification(messages.transactions()))
                .flatMap(contentSerializer::serialize)
                .ifPresent(content::add);

        return SpiNotification.builder()
                .content(content)
                .build();
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

    private InstitutionMessages getMessageContainer(String ispb) {
        return notifications.computeIfAbsent(ispb, key -> new InstitutionMessages());
    }
}