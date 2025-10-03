package br.kauan.spi.domain.services.notification;

import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.domain.services.notification.dto.SpiNotification;
import br.kauan.spi.port.input.NotificationUseCase;
import org.springframework.stereotype.Service;

@Service
public class NotificationService implements NotificationUseCase {

    private final NotificationOrchestrator notificationOrchestrator;

    public NotificationService(NotificationOrchestrator notificationOrchestrator) {
        this.notificationOrchestrator = notificationOrchestrator;
    }

    public void sendConfirmationNotification(PaymentTransaction paymentTransaction) {
        notificationOrchestrator.sendConfirmationNotification(paymentTransaction);
    }

    public void sendRejectionNotification(PaymentTransaction paymentTransaction) {
        notificationOrchestrator.sendRejectionNotification(paymentTransaction);
    }

    @Override
    public SpiNotification getNotifications(String ispb) {
        return notificationOrchestrator.getNotifications(ispb);
    }

    public void sendAcceptanceRequest(String ispb, PaymentTransaction paymentTransaction) {
        notificationOrchestrator.sendAcceptanceRequest(ispb, paymentTransaction);
    }
}