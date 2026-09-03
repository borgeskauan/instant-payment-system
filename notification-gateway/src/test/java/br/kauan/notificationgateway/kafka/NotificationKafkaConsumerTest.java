package br.kauan.notificationgateway.kafka;

import br.kauan.notificationgateway.delivery.DeliveryNotification;
import br.kauan.notificationgateway.delivery.PullRequestCoordinator;
import br.kauan.notificationgateway.delivery.RecentNotificationWindow;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class NotificationKafkaConsumerTest {

    @Test
    void appendsTheWholePollToTheSharedPartitionBufferAndSignalsItsRecipients() {
        RecentNotificationWindow window = new RecentNotificationWindow(20);
        PullRequestCoordinator coordinator = mock(PullRequestCoordinator.class);
        NotificationKafkaConsumer consumer = new NotificationKafkaConsumer(window, coordinator);

        consumer.consume(List.of(
                record(10, "20000001", "message-1"),
                record(11, "20000002", "message-2")
        ));

        assertThat(window.lookup(3, "20000001", 9, 15, 100).notifications())
                .extracting(DeliveryNotification::communicationId)
                .containsExactly("message-1");
        verify(coordinator).signal(java.util.Set.of("20000001", "20000002"));
    }

    @Test
    void malformedPollFailsWithoutSignallingRecipients() {
        RecentNotificationWindow window = new RecentNotificationWindow(20);
        PullRequestCoordinator coordinator = mock(PullRequestCoordinator.class);
        NotificationKafkaConsumer consumer = new NotificationKafkaConsumer(window, coordinator);
        ConsumerRecord<String, byte[]> invalid = record(11, "20000001", "message-2");
        invalid.headers().remove("notification.communication-id");

        assertThatThrownBy(() -> consumer.consume(List.of(
                record(10, "20000001", "message-1"),
                invalid
        ))).isInstanceOf(IllegalArgumentException.class);
        assertThat(window.lookup(3, "20000001", 9, 15, 100).state())
                .isEqualTo(RecentNotificationWindow.LookupState.HIT);
        verifyNoInteractions(coordinator);
    }

    private ConsumerRecord<String, byte[]> record(long offset, String ispb, String communicationId) {
        ConsumerRecord<String, byte[]> record = new ConsumerRecord<>(
                NotificationLog.TOPIC, 3, offset, ispb, communicationId.getBytes(StandardCharsets.UTF_8)
        );
        RecordHeaders headers = new RecordHeaders();
        headers.add("notification.communication-id", communicationId.getBytes(StandardCharsets.UTF_8));
        headers.forEach(record.headers()::add);
        return record;
    }
}
