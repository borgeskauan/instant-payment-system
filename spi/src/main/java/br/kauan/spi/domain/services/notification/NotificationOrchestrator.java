package br.kauan.spi.domain.services.notification;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.StatusReport;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static br.kauan.spi.Utils.getBankCode;

@Slf4j
@Component
public class NotificationOrchestrator {

    private final NotificationStorage notificationStorage;
    private final NotificationValidator validator;
    private final NotificationBuilder notificationBuilder;

    public NotificationOrchestrator(
            NotificationStorage notificationStorage,
            NotificationValidator validator,
            NotificationBuilder notificationBuilder
    ) {
        this.notificationStorage = notificationStorage;
        this.validator = validator;
        this.notificationBuilder = notificationBuilder;
    }

    public void sendConfirmationNotification(PaymentTransaction paymentTransaction) {
        sendConfirmationNotifications(List.of(paymentTransaction));
    }

    public void sendConfirmationNotifications(List<PaymentTransaction> paymentTransactions) {
        try {
            Map<String, List<StatusReport>> notificationsByIspb = new LinkedHashMap<>();

            for (PaymentTransaction paymentTransaction : paymentTransactions) {
                validator.validatePaymentTransaction(paymentTransaction);

                String receiverIspb = getBankCode(paymentTransaction.getReceiver());
                String senderIspb = getBankCode(paymentTransaction.getSender());

                log.debug("[PIX FLOW - Step 7] Building confirmation notifications for payment: {}",
                        paymentTransaction.getPaymentId());
                log.debug("[PIX FLOW - Step 7] Receiver ISPB: {}, Sender ISPB: {}", receiverIspb, senderIspb);

                StatusReport receiverNotification = notificationBuilder.buildStatusReport(
                        paymentTransaction, PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER);
                StatusReport senderNotification = notificationBuilder.buildStatusReport(
                        paymentTransaction, PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER);

                notificationsByIspb.computeIfAbsent(receiverIspb, ignored -> new ArrayList<>())
                        .add(receiverNotification);
                notificationsByIspb.computeIfAbsent(senderIspb, ignored -> new ArrayList<>())
                        .add(senderNotification);
            }

            notificationsByIspb.forEach((ispb, statusReports) -> {
                validator.validateIspb(ispb);
                notificationStorage.addStatusNotifications(ispb, statusReports);
                log.debug("[PIX FLOW - Step 7] Confirmation notification batch sent to PSP ({})", ispb);
            });

        } catch (Exception e) {
            log.error("[PIX FLOW - Error] Failed to send confirmation notification batch", e);
            throw new NotificationException("Failed to send confirmation notification", e);
        }
    }

    public void sendRejectionNotification(PaymentTransaction paymentTransaction) {
        sendRejectionNotifications(List.of(paymentTransaction));
    }

    public void sendRejectionNotifications(List<PaymentTransaction> paymentTransactions) {
        try {
            Map<String, List<StatusReport>> notificationsByIspb = new LinkedHashMap<>();

            for (PaymentTransaction paymentTransaction : paymentTransactions) {
                validator.validatePaymentTransaction(paymentTransaction);

                String senderIspb = getBankCode(paymentTransaction.getSender());
                StatusReport rejectionNotification = notificationBuilder.buildStatusReport(
                        paymentTransaction, PaymentStatus.REJECTED);

                notificationsByIspb.computeIfAbsent(senderIspb, ignored -> new ArrayList<>())
                        .add(rejectionNotification);
            }

            notificationsByIspb.forEach((ispb, statusReports) -> {
                validator.validateIspb(ispb);
                notificationStorage.addStatusNotifications(ispb, statusReports);
            });

            log.debug("Rejection notification batch sent. payments={}", paymentTransactions.size());
        } catch (Exception e) {
            log.error("Failed to send rejection notification batch", e);
            throw new NotificationException("Failed to send rejection notification", e);
        }
    }

    public void sendAcceptanceRequest(String ispb, PaymentTransaction paymentTransaction) {
        try {
            validator.validateIspb(ispb);
            validator.validatePaymentTransaction(paymentTransaction);

            log.debug("[PIX FLOW - Step 4] Sending acceptance request (PACS.008) to PSP Recebedor. ISPB: {}, Payment ID: {}",
                    ispb, paymentTransaction.getPaymentId());
            notificationStorage.addTransactionNotification(ispb, paymentTransaction);
            log.debug("[PIX FLOW - Step 4] Acceptance request queued for delivery via Kafka");
        } catch (Exception e) {
            log.error("[PIX FLOW - Error] Failed to send acceptance request for payment: {} to ISPB: {}",
                    paymentTransaction.getPaymentId(), ispb, e);
            throw new NotificationException("Failed to send acceptance request", e);
        }
    }

    public void sendAcceptanceRequests(List<PaymentTransaction> paymentTransactions) {
        try {
            Map<String, List<PaymentTransaction>> transactionsByIspb = new LinkedHashMap<>();

            for (PaymentTransaction paymentTransaction : paymentTransactions) {
                validator.validatePaymentTransaction(paymentTransaction);
                String receiverIspb = getBankCode(paymentTransaction.getReceiver());
                transactionsByIspb.computeIfAbsent(receiverIspb, ignored -> new ArrayList<>())
                        .add(paymentTransaction);
            }

            transactionsByIspb.forEach((ispb, transactions) -> {
                validator.validateIspb(ispb);
                log.debug("[PIX FLOW - Step 4] Sending acceptance request batch (PACS.008) to PSP Recebedor. ISPB: {}, payments={}",
                        ispb, transactions.size());
                notificationStorage.addTransactionNotifications(ispb, transactions);
            });
            log.debug("[PIX FLOW - Step 4] Acceptance request batch queued for delivery via Kafka");
        } catch (Exception e) {
            log.error("[PIX FLOW - Error] Failed to send acceptance request batch", e);
            throw new NotificationException("Failed to send acceptance request", e);
        }
    }
}
