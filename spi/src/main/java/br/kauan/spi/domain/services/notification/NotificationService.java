package br.kauan.spi.domain.services.notification;

import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.domain.services.notification.dto.SpiNotification;
import br.kauan.spi.port.input.NotificationUseCase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;

@Slf4j
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

    /**
     * Deprecated: Notifications are now pushed via Kafka.
     * This method is kept for backwards compatibility but returns empty result.
     * PSPs should consume from the notifications-topic Kafka topic instead.
     */
    @Override
    @Deprecated
    public DeferredResult<SpiNotification> getNotifications(String ispb) {
        log.warn("HTTP polling endpoint called for ISPB: {}. This is deprecated - use Kafka consumer instead.", ispb);
        DeferredResult<SpiNotification> result = new DeferredResult<>();
        result.setResult(SpiNotification.empty());
        return result;
    }

    public void sendAcceptanceRequest(String ispb, PaymentTransaction paymentTransaction) {
        notificationOrchestrator.sendAcceptanceRequest(ispb, paymentTransaction);
    }
}