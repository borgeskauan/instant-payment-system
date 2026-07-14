package br.kauan.spi.domain.services.notification;

import br.kauan.spi.adapter.output.kafka.NotificationPublisher;
import br.kauan.spi.adapter.output.kafka.NotificationPublication;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.domain.services.notification.payload.NotificationPayloadFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;

@Slf4j
@Component
public class NotificationStorage {

    private static final String ACCEPTANCE_REQUEST = "ACCEPTANCE_REQUEST";
    private static final String REJECTED_NOTIFICATION = "REJECTED_NOTIFICATION";
    private static final String SETTLED_NOTIFICATION = "SETTLED_NOTIFICATION";

    private final NotificationPayloadFactory payloadFactory;
    private final NotificationContentSerializer contentSerializer;
    private final NotificationPublisher notificationPublisher;

    public NotificationStorage(
            NotificationPayloadFactory payloadFactory,
            NotificationPublisher notificationPublisher
    ) {
        this.payloadFactory = payloadFactory;
        this.notificationPublisher = notificationPublisher;
        this.contentSerializer = new NotificationContentSerializer();
    }

    public void addStatusNotifications(Map<String, List<StatusReportCommand>> statusReportsByIspb) {
        List<NotificationPublication> notifications = new ArrayList<>();

        statusReportsByIspb.forEach((ispb, statusReports) -> {
            log.debug("Publishing status notifications for ISPB: {}", ispb);

            for (StatusReportCommand statusReport : statusReports) {
                Object statusNotification = createStatusNotification(List.of(statusReport));
                contentSerializer.serialize(statusNotification)
                        .ifPresent(json -> notifications.add(NotificationPublication.create(
                                ispb,
                                json,
                                statusEventType(statusReport),
                                statusReport.getOriginalPaymentId(),
                                notificationStatus(statusReport)
                        )));
            }
        });

        notificationPublisher.publishNotifications(notifications);
    }

    public void addTransactionNotifications(Map<String, List<PaymentTransactionCommand>> paymentTransactionsByIspb) {
        List<NotificationPublication> notifications = new ArrayList<>();

        paymentTransactionsByIspb.forEach((ispb, paymentTransactions) -> {
            log.debug("Publishing transaction notifications for ISPB: {}", ispb);

            for (PaymentTransactionCommand paymentTransaction : paymentTransactions) {
                Object paymentNotification = createPaymentNotification(List.of(paymentTransaction));
                contentSerializer.serialize(paymentNotification)
                        .ifPresent(json -> notifications.add(NotificationPublication.create(
                                ispb,
                                json,
                                ACCEPTANCE_REQUEST,
                                paymentTransaction.getPaymentId(),
                                null
                        )));
            }
        });

        notificationPublisher.publishNotifications(notifications);
    }

    private Object createStatusNotification(List<StatusReportCommand> statusReports) {
        if (statusReports.isEmpty()) {
            return null;
        }

        return payloadFactory.statusNotification(statusReports);
    }

    private Object createPaymentNotification(List<PaymentTransactionCommand> transactions) {
        if (transactions.isEmpty()) {
            return null;
        }

        return payloadFactory.paymentNotification(transactions);
    }

    private String statusEventType(StatusReportCommand statusReport) {
        return statusReport.getStatus() == PaymentStatus.REJECTED
                ? REJECTED_NOTIFICATION
                : SETTLED_NOTIFICATION;
    }

    private String notificationStatus(StatusReportCommand statusReport) {
        return switch (statusReport.getStatus()) {
            case ACCEPTED_AND_SETTLED_FOR_RECEIVER -> "ACCC";
            case ACCEPTED_AND_SETTLED_FOR_SENDER -> "ACSC";
            case REJECTED -> "RJCT";
            case ACCEPTED_IN_PROCESS, WAITING_ACCEPTANCE, ACCEPTED_AND_SETTLED ->
                    throw new IllegalArgumentException("No notification status for: " + statusReport.getStatus());
        };
    }
}
