package br.kauan.notificationgateway.delivery;

import br.kauan.notificationgateway.config.NotificationGatewayProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class NotificationDeliveryReader {

    private final RecentNotificationWindow recentWindow;
    private final NotificationHistory history;
    private final int scanLimit;

    @Autowired
    public NotificationDeliveryReader(
            RecentNotificationWindow recentWindow,
            NotificationHistory history,
            NotificationGatewayProperties properties
    ) {
        this(recentWindow, history, properties.pull().kafkaScanLimit());
    }

    NotificationDeliveryReader(
            RecentNotificationWindow recentWindow,
            NotificationHistory history,
            int scanLimit
    ) {
        this.recentWindow = recentWindow;
        this.history = history;
        this.scanLimit = scanLimit;
    }

    public DeliveryPage read(
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
        return new DeliveryPage(
                lookup.notifications(),
                lookup.lastExaminedOffset(),
                lookup.atTail()
        );
    }
}
