package br.kauan.notificationgateway.delivery;

import br.kauan.notificationgateway.kafka.HistoricalKafkaReader;
import br.kauan.notificationgateway.kafka.KafkaNotificationPage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class NotificationDeliveryReader {

    private final RecentNotificationWindow recentWindow;
    private final HistoricalKafkaReader history;
    private final int scanLimit;

    public NotificationDeliveryReader(
            RecentNotificationWindow recentWindow,
            HistoricalKafkaReader history,
            @Value("${notification-gateway.pull.kafka-scan-limit:4096}") int scanLimit
    ) {
        if (scanLimit < 15) {
            throw new IllegalArgumentException("Kafka scan limit must be at least 15");
        }
        this.recentWindow = recentWindow;
        this.history = history;
        this.scanLimit = scanLimit;
    }

    public KafkaNotificationPage read(
            String recipientIspb,
            int partition,
            long afterOffset,
            int notificationLimit
    ) {
        RecentNotificationWindow.Lookup lookup = recentWindow.lookup(
                partition,
                recipientIspb,
                afterOffset,
                notificationLimit,
                scanLimit
        );
        if (lookup.state() == RecentNotificationWindow.LookupState.MISS) {
            return history.read(recipientIspb, partition, afterOffset, notificationLimit, scanLimit);
        }
        return new KafkaNotificationPage(
                lookup.notifications(),
                lookup.lastExaminedOffset(),
                lookup.atTail()
        );
    }
}
