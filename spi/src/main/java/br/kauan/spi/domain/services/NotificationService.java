package br.kauan.spi.domain.services;

import br.kauan.spi.adapter.input.dtos.pacs.PaymentTransactionMapper;
import br.kauan.spi.adapter.input.dtos.pacs.StatusReportMapper;
import br.kauan.spi.adapter.input.dtos.pacs.pacs002.FIToFIPaymentStatusReport;
import br.kauan.spi.adapter.input.dtos.pacs.pacs008.FIToFICustomerCreditTransfer;
import br.kauan.spi.domain.entity.commons.BatchDetails;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.StatusBatch;
import br.kauan.spi.domain.entity.status.StatusReport;
import br.kauan.spi.domain.entity.transfer.PaymentBatch;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.port.input.NotificationUseCase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static br.kauan.spi.Utils.getBankCode;

@Service
public class NotificationService implements NotificationUseCase {

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
    public SpiNotification getNotifications(String ispb) {
        InstitutionMessages messages = notifications.remove(ispb);

        if (messages == null || messages.isEmpty()) {
            return SpiNotification.builder()
                    .content(Collections.emptyList())
                    .build();
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

    private SpiNotification convertToSpiNotifications(InstitutionMessages messages) {
        var statusNotification = createStatusBatchNotification(messages.statuses());
        var paymentNotification = createPaymentBatchNotification(messages.transactions());

        var content = createContent(statusNotification, paymentNotification);

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

    private record InstitutionMessages(List<StatusReport> statuses, List<PaymentTransaction> transactions) {
        InstitutionMessages() {
            this(new ArrayList<>(), new ArrayList<>());
        }

        boolean isEmpty() {
            return statuses.isEmpty() && transactions.isEmpty();
        }
    }

    private List<String> createContent(Object... objects) {
        try {
            List<String> content = new ArrayList<>();
            for (Object obj : objects) {
                if (obj != null) {
                    var serializedObj = objectMapper.writeValueAsString(obj);
                    content.add(serializedObj);
                }
            }

            return content;

        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error serializing notification content", e);
        }
    }
}