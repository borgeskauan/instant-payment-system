package br.kauan.notificationgateway.kafka;

import br.kauan.notificationgateway.delivery.RecentNotificationBuffer;
import br.kauan.notificationgateway.grpc.PullRequestCoordinator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Tails the durable notification log into the bounded in-memory fast path. */
@Component
public class NotificationKafkaConsumer {

    private static final String NOTIFICATIONS_TOPIC = "psp-notifications-v1";

    private final RecentNotificationBuffer buffer;
    private final PullRequestCoordinator coordinator;

    public NotificationKafkaConsumer(
            RecentNotificationBuffer buffer,
            PullRequestCoordinator coordinator
    ) {
        this.buffer = buffer;
        this.coordinator = coordinator;
    }

    @KafkaListener(
            topics = NOTIFICATIONS_TOPIC,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "notificationKafkaListenerContainerFactory"
    )
    public void consume(List<ConsumerRecord<String, byte[]>> records) {
        if (records.isEmpty()) {
            return;
        }

        List<KafkaNotificationRecord> notifications = new ArrayList<>(records.size());
        Set<String> recipients = new HashSet<>();
        for (ConsumerRecord<String, byte[]> record : records) {
            KafkaNotificationRecord notification = KafkaNotificationRecordMapper.map(record);
            notifications.add(notification);
            recipients.add(notification.recipientIspb());
        }
        buffer.addAll(notifications);
        coordinator.signal(Set.copyOf(recipients));
    }
}
