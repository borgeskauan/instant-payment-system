package br.kauan.spi.domain.services;

import br.kauan.spi.adapter.input.dtos.pacs.PaymentTransactionMapper;
import br.kauan.spi.adapter.input.dtos.pacs.StatusReportMapper;
import br.kauan.spi.domain.entity.commons.BatchDetails;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.StatusBatch;
import br.kauan.spi.domain.entity.status.StatusReport;
import br.kauan.spi.domain.entity.transfer.PaymentBatch;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.port.input.NotificationUseCase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static br.kauan.spi.Utils.getBankCode;

@Service
public class NotificationService implements NotificationUseCase {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    private final Map<String, InstitutionMessages> notifications = new ConcurrentHashMap<>();
    private final StatusReportMapper statusReportMapper;
    private final PaymentTransactionMapper paymentTransactionMapper;
    private final ObjectMapper objectMapper;

    public NotificationService(StatusReportMapper statusReportMapper,
                               PaymentTransactionMapper paymentTransactionMapper,
                               ObjectMapper objectMapper) {
        this.statusReportMapper = statusReportMapper;
        this.paymentTransactionMapper = paymentTransactionMapper;
        this.objectMapper = objectMapper;
    }

    public void sendConfirmationNotification(PaymentTransaction paymentTransaction) {
        String receiverIspb = getBankCode(paymentTransaction.getReceiver());
        String senderIspb = getBankCode(paymentTransaction.getSender());

        StatusReport receiverNotification = buildStatusReport(paymentTransaction, PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER);
        StatusReport senderNotification = buildStatusReport(paymentTransaction, PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER);

        addStatusNotification(receiverIspb, receiverNotification);
        addStatusNotification(senderIspb, senderNotification);
    }

    public void sendRejectionNotification(PaymentTransaction paymentTransaction) {
        String senderIspb = getBankCode(paymentTransaction.getSender());
        StatusReport rejectionNotification = buildStatusReport(paymentTransaction, PaymentStatus.REJECTED);

        addStatusNotification(senderIspb, rejectionNotification);
    }

    @Override
    public List<SpiNotification> getNotifications(String ispb) {
        InstitutionMessages messages = notifications.remove(ispb);

        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }

        return convertToSpiNotifications(messages);
    }

    public void sendAcceptanceRequest(String ispb, PaymentTransaction paymentTransaction) {
        getMessageContainer(ispb).transactions().add(paymentTransaction);
    }

    private StatusReport buildStatusReport(PaymentTransaction paymentTransaction, PaymentStatus paymentStatus) {
        return StatusReport.builder()
                .originalPaymentId(paymentTransaction.getPaymentId())
                .status(paymentStatus)
                .build();
    }

    private void addStatusNotification(String ispb, StatusReport statusReport) {
        getMessageContainer(ispb).statuses().add(statusReport);
    }

    private List<SpiNotification> convertToSpiNotifications(InstitutionMessages messages) {
        try {
            SpiNotification statusNotification = createStatusBatchNotification(messages.statuses());
            SpiNotification paymentNotification = createPaymentBatchNotification(messages.transactions());

            return List.of(statusNotification, paymentNotification);
        } catch (JsonProcessingException e) {
            logger.error("Failed to convert messages to SPI notifications", e);
            throw new NotificationProcessingException("Failed to process notifications", e);
        }
    }

    private SpiNotification createStatusBatchNotification(List<StatusReport> statusReports) throws JsonProcessingException {
        BatchDetails batchDetails = BatchDetails.of(statusReports.size());
        StatusBatch statusBatch = StatusBatch.builder()
                .batchDetails(batchDetails)
                .statusReports(statusReports)
                .build();

        Object regulatoryReport = statusReportMapper.toRegulatoryReport(statusBatch);
        return serializeToSpiNotification(regulatoryReport);
    }

    private SpiNotification createPaymentBatchNotification(List<PaymentTransaction> transactions) throws JsonProcessingException {
        BatchDetails batchDetails = BatchDetails.of(transactions.size());
        PaymentBatch paymentBatch = PaymentBatch.builder()
                .batchDetails(batchDetails)
                .transactions(transactions)
                .build();

        Object regulatoryTransactions = paymentTransactionMapper.toRegulatoryRequest(paymentBatch);
        return serializeToSpiNotification(regulatoryTransactions);
    }

    private SpiNotification serializeToSpiNotification(Object obj) throws JsonProcessingException {
        String content = objectMapper.writeValueAsString(obj);
        return SpiNotification.builder().content(content).build();
    }

    private InstitutionMessages getMessageContainer(String ispb) {
        return notifications.computeIfAbsent(ispb, key -> new InstitutionMessages());
    }

    private record InstitutionMessages(List<StatusReport> statuses, List<PaymentTransaction> transactions) {
        InstitutionMessages() {
            this(new ArrayList<>(), new ArrayList<>());
        }

        boolean isEmpty() {
            return statuses.isEmpty() && transactions.isEmpty();
        }
    }

    private static class NotificationProcessingException extends RuntimeException {
        NotificationProcessingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}