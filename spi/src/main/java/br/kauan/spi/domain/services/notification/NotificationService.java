package br.kauan.spi.domain.services.notification;

import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class NotificationService {

    private final NotificationOrchestrator notificationOrchestrator;

    public NotificationService(NotificationOrchestrator notificationOrchestrator) {
        this.notificationOrchestrator = notificationOrchestrator;
    }

    public void sendConfirmationNotification(PaymentTransaction paymentTransaction) {
        notificationOrchestrator.sendConfirmationNotification(paymentTransaction);
    }

    public void sendConfirmationNotifications(List<PaymentTransaction> paymentTransactions) {
        notificationOrchestrator.sendConfirmationNotifications(paymentTransactions);
    }

    public void sendRejectionNotification(PaymentTransaction paymentTransaction) {
        notificationOrchestrator.sendRejectionNotification(paymentTransaction);
    }

    public void sendRejectionNotifications(List<PaymentTransaction> paymentTransactions) {
        notificationOrchestrator.sendRejectionNotifications(paymentTransactions);
    }

    public void sendAcceptanceRequest(String ispb, PaymentTransaction paymentTransaction) {
        notificationOrchestrator.sendAcceptanceRequest(ispb, paymentTransaction);
    }

    public void sendAcceptanceRequests(List<PaymentTransaction> paymentTransactions) {
        notificationOrchestrator.sendAcceptanceRequests(paymentTransactions);
    }
}
