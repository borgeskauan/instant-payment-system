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
     * DEPRECATED: Returns empty results immediately.
     * 
     * Replaced by Kafka notifications on 'notifications-topic'.
     * K6 tests still use this endpoint - migration in progress.
     * 
     * @param ispb The ISPB requesting notifications
     * @return Empty result
     * @deprecated Use Kafka consumer instead
     * @see docs/LOAD_TEST_KAFKA_INTEGRATION.md
     */
    @Override
    @Deprecated
    public DeferredResult<SpiNotification> getNotifications(String ispb) {
        log.warn("Deprecated endpoint called for ISPB: {}. Use Kafka notifications-topic instead.", ispb);
        DeferredResult<SpiNotification> result = new DeferredResult<>();
        result.setResult(SpiNotification.empty());
        return result;
    }

    public void sendAcceptanceRequest(String ispb, PaymentTransaction paymentTransaction) {
        notificationOrchestrator.sendAcceptanceRequest(ispb, paymentTransaction);
    }
}