package br.kauan.notificationgateway.kafka;

import br.kauan.notificationgateway.delivery.RecentNotificationWindow;
import br.kauan.notificationgateway.grpc.PullRequestCoordinator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Tails the durable notification log into the bounded in-memory fast path. */
@Component
public class NotificationKafkaConsumer {

    private final RecentNotificationWindow recentWindow;
    private final PullRequestCoordinator coordinator;

    public NotificationKafkaConsumer(
            RecentNotificationWindow recentWindow,
            PullRequestCoordinator coordinator
    ) {
        this.recentWindow = recentWindow;
        this.coordinator = coordinator;
    }

    @KafkaListener(
            topics = NotificationLog.TOPIC,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "notificationKafkaListenerContainerFactory"
    )
    public void consume(List<ConsumerRecord<String, byte[]>> records) {
        if (records.isEmpty()) {
            return;
        }

        Set<String> recipients = new HashSet<>();
        for (ConsumerRecord<String, byte[]> record : records) {
            KafkaNotificationRecord notification = KafkaNotificationRecordMapper.map(record);
            recentWindow.add(notification);
            recipients.add(notification.recipientIspb());
        }
        coordinator.signal(recipients);
    }
}
