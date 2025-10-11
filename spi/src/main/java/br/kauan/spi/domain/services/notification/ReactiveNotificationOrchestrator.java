package br.kauan.spi.domain.services.notification;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.StatusReport;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.domain.services.notification.dto.SpiNotification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import static br.kauan.spi.Utils.getBankCode;

@Slf4j
@Component
public class ReactiveNotificationOrchestrator {

    private final ReactiveNotificationStorage notificationStorage;
    private final NotificationValidator validator;
    private final NotificationBuilder notificationBuilder;

    public ReactiveNotificationOrchestrator(
            ReactiveNotificationStorage notificationStorage,
            NotificationValidator validator,
            NotificationBuilder notificationBuilder
    ) {
        this.notificationStorage = notificationStorage;
        this.validator = validator;
        this.notificationBuilder = notificationBuilder;
    }

    public Mono<Void> sendConfirmationNotification(PaymentTransaction paymentTransaction) {
        return Mono.fromRunnable(() -> validator.validatePaymentTransaction(paymentTransaction))
                .then(Mono.fromCallable(() -> {
                    String receiverIspb = getBankCode(paymentTransaction.getReceiver());
                    String senderIspb = getBankCode(paymentTransaction.getSender());

                    StatusReport receiverNotification = notificationBuilder.buildStatusReport(
                            paymentTransaction, PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER);
                    StatusReport senderNotification = notificationBuilder.buildStatusReport(
                            paymentTransaction, PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER);

                    return new NotificationPair(receiverIspb, receiverNotification, senderIspb, senderNotification);
                }))
                .flatMap(pair ->
                        notificationStorage.addStatusNotification(pair.receiverIspb, pair.receiverNotification)
                                .then(notificationStorage.addStatusNotification(pair.senderIspb, pair.senderNotification))
                )
                .doOnSuccess(unused ->
                        log.debug("Confirmation notification sent for payment: {}", paymentTransaction.getPaymentId()))
                .doOnError(error ->
                        log.error("Failed to send confirmation notification for payment: {}",
                                paymentTransaction.getPaymentId(), error))
                .onErrorMap(error -> new NotificationException("Failed to send confirmation notification", error));
    }

    public Mono<Void> sendRejectionNotification(PaymentTransaction paymentTransaction) {
        return Mono.fromRunnable(() -> validator.validatePaymentTransaction(paymentTransaction))
                .then(Mono.fromCallable(() -> {
                    String senderIspb = getBankCode(paymentTransaction.getSender());
                    StatusReport rejectionNotification = notificationBuilder.buildStatusReport(
                            paymentTransaction, PaymentStatus.REJECTED);
                    return new IspbNotification(senderIspb, rejectionNotification);
                }))
                .flatMap(pair -> notificationStorage.addStatusNotification(pair.ispb, pair.notification))
                .doOnSuccess(unused ->
                        log.debug("Rejection notification sent for payment: {}", paymentTransaction.getPaymentId()))
                .doOnError(error ->
                        log.error("Failed to send rejection notification for payment: {}",
                                paymentTransaction.getPaymentId(), error))
                .onErrorMap(error -> new NotificationException("Failed to send rejection notification", error));
    }

    public Mono<SpiNotification> getNotifications(String ispb) {
        return Mono.fromRunnable(() -> validator.validateIspb(ispb))
                .then(notificationStorage.getNotifications(ispb))
                .doOnNext(notification ->
                        log.debug("Retrieved notifications for ISPB: {}, content size: {}",
                                ispb, notification.getContent().size()))
                .doOnError(error ->
                        log.error("Failed to get notifications for ISPB: {}", ispb, error))
                .onErrorMap(error -> new NotificationException("Failed to retrieve notifications", error));
    }

    public Mono<Void> sendAcceptanceRequest(String ispb, PaymentTransaction paymentTransaction) {
        return Mono.fromRunnable(() -> {
                    validator.validateIspb(ispb);
                    validator.validatePaymentTransaction(paymentTransaction);
                })
                .then(notificationStorage.addTransactionNotification(ispb, paymentTransaction))
                .doOnError(error ->
                        log.error("Failed to send acceptance request for payment: {} to ISPB: {}",
                                paymentTransaction.getPaymentId(), ispb, error))
                .onErrorMap(error -> new NotificationException("Failed to send acceptance request", error));
    }

    // Helper records for cleaner code
    private record NotificationPair(String receiverIspb, StatusReport receiverNotification,
                                    String senderIspb, StatusReport senderNotification) {}

    private record IspbNotification(String ispb, StatusReport notification) {}
}