package br.kauan.spi.domain.services.notification;

import br.kauan.spi.adapter.output.kafka.NotificationPublisher;
import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.domain.services.notification.payload.NotificationPayloadFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class NotificationStorage {

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
        Map<String, String> notificationsByIspb = new LinkedHashMap<>();

        statusReportsByIspb.forEach((ispb, statusReports) -> {
            log.debug("Publishing status notification for ISPB: {}", ispb);

            Object statusNotification = createStatusNotification(statusReports);

            if (statusNotification != null) {
                contentSerializer.serialize(statusNotification)
                        .ifPresent(json -> notificationsByIspb.put(ispb, json));
            }
        });

        notificationPublisher.publishNotifications(notificationsByIspb);
    }

    public void addTransactionNotifications(Map<String, List<PaymentTransactionCommand>> paymentTransactionsByIspb) {
        Map<String, String> notificationsByIspb = new LinkedHashMap<>();

        paymentTransactionsByIspb.forEach((ispb, paymentTransactions) -> {
            log.debug("Publishing transaction notification for ISPB: {}", ispb);

            Object paymentNotification = createPaymentNotification(paymentTransactions);

            if (paymentNotification != null) {
                contentSerializer.serialize(paymentNotification)
                        .ifPresent(json -> notificationsByIspb.put(ispb, json));
            }
        });

        notificationPublisher.publishNotifications(notificationsByIspb);
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
}
