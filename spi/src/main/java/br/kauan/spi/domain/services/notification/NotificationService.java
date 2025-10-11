package br.kauan.spi.domain.services.notification;

import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.domain.services.notification.dto.SpiNotification;
import br.kauan.spi.port.input.NotificationUseCase;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class NotificationService implements NotificationUseCase {

    private final ReactiveNotificationOrchestrator notificationOrchestrator;

    public NotificationService(ReactiveNotificationOrchestrator notificationOrchestrator) {
        this.notificationOrchestrator = notificationOrchestrator;
    }

    public Mono<Void> sendConfirmationNotification(PaymentTransaction paymentTransaction) {
        return notificationOrchestrator.sendConfirmationNotification(paymentTransaction);
    }

    public Mono<Void> sendRejectionNotification(PaymentTransaction paymentTransaction) {
        return notificationOrchestrator.sendRejectionNotification(paymentTransaction);
    }

    @Override
    public Mono<SpiNotification> getNotifications(String ispb) {
        return notificationOrchestrator.getNotifications(ispb);
    }

    public Mono<Void> sendAcceptanceRequest(String ispb, PaymentTransaction paymentTransaction) {
        return notificationOrchestrator.sendAcceptanceRequest(ispb, paymentTransaction);
    }
}