package br.kauan.spi.domain.services.notification;

import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
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

    public void sendConfirmationNotifications(List<PaymentTransactionCommand> paymentTransactions) {
        notificationOrchestrator.sendConfirmationNotifications(paymentTransactions);
    }

    public void sendRejectionNotifications(List<PaymentTransactionCommand> paymentTransactions) {
        notificationOrchestrator.sendRejectionNotifications(paymentTransactions);
    }

    public void sendAcceptanceRequests(List<PaymentTransactionCommand> paymentTransactions) {
        notificationOrchestrator.sendAcceptanceRequests(paymentTransactions);
    }
}
