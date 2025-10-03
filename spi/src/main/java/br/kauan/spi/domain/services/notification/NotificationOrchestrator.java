package br.kauan.spi.domain.services.notification;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.StatusReport;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.domain.services.notification.dto.SpiNotification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
        try {
            validator.validatePaymentTransaction(paymentTransaction);

            String receiverIspb = getBankCode(paymentTransaction.getReceiver());
            String senderIspb = getBankCode(paymentTransaction.getSender());

            StatusReport receiverNotification = notificationBuilder.buildStatusReport(
                    paymentTransaction, PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER);
            StatusReport senderNotification = notificationBuilder.buildStatusReport(
                    paymentTransaction, PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER);

            notificationStorage.addStatusNotification(receiverIspb, receiverNotification);
            notificationStorage.addStatusNotification(senderIspb, senderNotification);

            log.debug("Confirmation notification sent for payment: {}", paymentTransaction.getPaymentId());
        } catch (Exception e) {
            log.error("Failed to send confirmation notification for payment: {}", paymentTransaction.getPaymentId(), e);
            throw new NotificationException("Failed to send confirmation notification", e);
        }
    }

    public void sendRejectionNotification(PaymentTransaction paymentTransaction) {
        try {
            validator.validatePaymentTransaction(paymentTransaction);

            String senderIspb = getBankCode(paymentTransaction.getSender());
            StatusReport rejectionNotification = notificationBuilder.buildStatusReport(
                    paymentTransaction, PaymentStatus.REJECTED);

            notificationStorage.addStatusNotification(senderIspb, rejectionNotification);

            log.debug("Rejection notification sent for payment: {}", paymentTransaction.getPaymentId());
        } catch (Exception e) {
            log.error("Failed to send rejection notification for payment: {}", paymentTransaction.getPaymentId(), e);
            throw new NotificationException("Failed to send rejection notification", e);
        }
    }

    public SpiNotification getNotifications(String ispb) {
        try {
            validator.validateIspb(ispb);
            return notificationStorage.retrieveAndClearNotifications(ispb);
        } catch (Exception e) {
            log.error("Failed to get notifications for ISPB: {}", ispb, e);
            throw new NotificationException("Failed to retrieve notifications", e);
        }
    }

    public void sendAcceptanceRequest(String ispb, PaymentTransaction paymentTransaction) {
        try {
            validator.validateIspb(ispb);
            validator.validatePaymentTransaction(paymentTransaction);

            notificationStorage.addTransactionNotification(ispb, paymentTransaction);
            log.debug("Acceptance request sent for payment: {} to ISPB: {}",
                    paymentTransaction.getPaymentId(), ispb);
        } catch (Exception e) {
            log.error("Failed to send acceptance request for payment: {} to ISPB: {}",
                    paymentTransaction.getPaymentId(), ispb, e);
            throw new NotificationException("Failed to send acceptance request", e);
        }
    }
}