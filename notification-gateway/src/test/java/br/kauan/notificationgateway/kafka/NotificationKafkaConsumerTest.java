package br.kauan.notificationgateway.kafka;

import br.kauan.notificationgateway.delivery.IncomingNotification;
import br.kauan.notificationgateway.delivery.NotificationIndexingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;

class NotificationKafkaConsumerTest {

    @Test
    void decodesTheWholeKafkaPollBeforeEnsuringItsNotificationsAreIndexed() {
        NotificationIndexingService indexingService = mock(NotificationIndexingService.class);
        NotificationKafkaConsumer consumer = new NotificationKafkaConsumer(indexingService);
        ConsumerRecord<String, byte[]> first = record(10, "20000001", "v1:first", "E2E-1", "ACSC", "first");
        ConsumerRecord<String, byte[]> second = record(11, "20000002", "v1:second", "E2E-2", "RJCT", "second");

        consumer.consume(List.of(first, second));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IncomingNotification>> notifications = ArgumentCaptor.forClass(List.class);
        verify(indexingService).ensureIndexed(notifications.capture());
        assertThat(notifications.getValue()).extracting(
                IncomingNotification::communicationId,
                IncomingNotification::recipientIspb,
                IncomingNotification::paymentId,
                IncomingNotification::status
        ).containsExactly(
                tuple("v1:first", "20000001", "E2E-1", "ACSC"),
                tuple("v1:second", "20000002", "E2E-2", "RJCT")
        );
    }

    @Test
    void malformedRecordPreventsAnyPersistenceOrSignal() {
        NotificationIndexingService indexingService = mock(NotificationIndexingService.class);
        NotificationKafkaConsumer consumer = new NotificationKafkaConsumer(indexingService);
        ConsumerRecord<String, byte[]> invalid = record(10, "20000001", "v1:first", "E2E-1", "ACSC", "first");
        invalid.headers().remove("notification.schema-version");

        assertThatThrownBy(() -> consumer.consume(List.of(invalid)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing Kafka header: notification.schema-version");
        verifyNoInteractions(indexingService);
    }

    private ConsumerRecord<String, byte[]> record(
            long offset,
            String ispb,
            String communicationId,
            String paymentId,
            String status,
            String payload
    ) {
        ConsumerRecord<String, byte[]> record = new ConsumerRecord<>(
                "psp-notifications", 1, offset, ispb, payload.getBytes(StandardCharsets.UTF_8)
        );
        RecordHeaders headers = new RecordHeaders();
        headers.add("notification.communication-id", bytes(communicationId));
        headers.add("notification.event-type", bytes("SETTLED_NOTIFICATION"));
        headers.add("notification.payment-id", bytes(paymentId));
        headers.add("notification.status", bytes(status));
        headers.add("notification.schema-version", bytes("v1"));
        headers.forEach(record.headers()::add);
        return record;
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
