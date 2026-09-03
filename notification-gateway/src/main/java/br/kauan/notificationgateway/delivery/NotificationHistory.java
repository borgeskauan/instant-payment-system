package br.kauan.notificationgateway.delivery;

public interface NotificationHistory {

    DeliveryPage read(
            String recipientIspb,
            int partition,
            long afterOffset,
            int notificationLimit,
            int scanLimit
    );
}
