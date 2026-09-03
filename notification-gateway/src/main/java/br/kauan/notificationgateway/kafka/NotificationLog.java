package br.kauan.notificationgateway.kafka;

public final class NotificationLog {

    public static final String TOPIC = "psp-notifications-v1";
    public static final String GENERATION = TOPIC;
    public static final int PARTITION_COUNT = 8;

    private NotificationLog() {
    }
}
