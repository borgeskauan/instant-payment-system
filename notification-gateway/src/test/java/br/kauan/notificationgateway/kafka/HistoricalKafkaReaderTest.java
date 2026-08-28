package br.kauan.notificationgateway.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoricalKafkaReaderTest {

    @Test
    void filtersASharedPartitionAndAdvancesThroughEveryExaminedRecord() {
        Consumer<String, byte[]> consumer = consumer(0, 5, List.of(
                record(0, "20000002", "other-0"),
                record(1, "20000001", "wanted-1"),
                record(2, "20000002", "other-2"),
                record(3, "20000001", "wanted-3"),
                record(4, "20000002", "other-4")
        ));
        HistoricalKafkaReader reader = new HistoricalKafkaReader(ignored -> consumer, Duration.ofMillis(1));

        KafkaNotificationPage page = reader.read("20000001", 3, -1, 15, 100);

        assertThat(page.notifications()).extracting(KafkaNotificationRecord::communicationId)
                .containsExactly("wanted-1", "wanted-3");
        assertThat(page.lastExaminedOffset()).isEqualTo(4);
        assertThat(page.atTail()).isTrue();
        verify(consumer).seek(new TopicPartition(NotificationLog.TOPIC, 3), 0);
    }

    @Test
    void stopsAtTheFifteenthMatchWithoutSkippingTheNextNotification() {
        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        for (int offset = 0; offset < 20; offset++) {
            records.add(record(offset, "20000001", "message-" + offset));
        }
        Consumer<String, byte[]> consumer = consumer(0, 20, records);
        HistoricalKafkaReader reader = new HistoricalKafkaReader(ignored -> consumer, Duration.ofMillis(1));

        KafkaNotificationPage page = reader.read("20000001", 3, -1, 15, 100);

        assertThat(page.notifications()).hasSize(15);
        assertThat(page.lastExaminedOffset()).isEqualTo(14);
        assertThat(page.atTail()).isFalse();
    }

    @Test
    void rejectsAnIssuedCursorWhoseNextOffsetWasDeletedByRetention() {
        Consumer<String, byte[]> consumer = consumer(100, 120, List.of());
        HistoricalKafkaReader reader = new HistoricalKafkaReader(ignored -> consumer, Duration.ofMillis(1));

        assertThatThrownBy(() -> reader.read("20000001", 3, 20, 15, 100))
                .isInstanceOf(NotificationCursorExpiredException.class);
    }

    @SuppressWarnings("unchecked")
    private Consumer<String, byte[]> consumer(
            long beginning,
            long end,
            List<ConsumerRecord<String, byte[]>> records
    ) {
        Consumer<String, byte[]> consumer = mock(Consumer.class);
        TopicPartition partition = new TopicPartition(NotificationLog.TOPIC, 3);
        when(consumer.beginningOffsets(any())).thenReturn(Map.of(partition, beginning));
        when(consumer.endOffsets(any())).thenReturn(Map.of(partition, end));
        when(consumer.poll(any(Duration.class))).thenReturn(new ConsumerRecords<>(Map.of(partition, records)));
        return consumer;
    }

    private ConsumerRecord<String, byte[]> record(long offset, String recipient, String communicationId) {
        ConsumerRecord<String, byte[]> record = new ConsumerRecord<>(
                NotificationLog.TOPIC,
                3,
                offset,
                recipient,
                communicationId.getBytes(StandardCharsets.UTF_8)
        );
        RecordHeaders headers = new RecordHeaders();
        headers.add("notification.communication-id", communicationId.getBytes(StandardCharsets.UTF_8));
        headers.forEach(record.headers()::add);
        return record;
    }
}
