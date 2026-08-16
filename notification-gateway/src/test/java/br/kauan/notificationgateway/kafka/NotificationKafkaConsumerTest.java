package br.kauan.notificationgateway.kafka;

import br.kauan.notificationgateway.delivery.IncomingNotification;
import br.kauan.notificationgateway.delivery.NotificationDeliveryRepository;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class NotificationKafkaConsumerTest {

    @Test
    void consumesKafkaPollAsOnePersistentBatch() {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        NotificationKafkaConsumer consumer = new NotificationKafkaConsumer(repository);
        ConsumerRecord<String, byte[]> first = notificationRecord(
                10L,
                "20000001",
                "v1:first",
                "E2E-1",
                "ACSC",
                "first-payload"
        );
        ConsumerRecord<String, byte[]> second = notificationRecord(
                11L,
                "20000002",
                "v1:second",
                "E2E-2",
                "RJCT",
                "second-payload"
        );

        consumer.consume(List.of(first, second));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IncomingNotification>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAllIfAbsent(captor.capture());
        assertThat(captor.getValue())
                .extracting(
                        IncomingNotification::communicationId,
                        IncomingNotification::recipientIspb,
                        IncomingNotification::eventType,
                        IncomingNotification::paymentId,
                        IncomingNotification::status,
                        IncomingNotification::schemaVersion
                )
                .containsExactly(
                        tuple("v1:first", "20000001", "SETTLED_NOTIFICATION", "E2E-1", "ACSC", "v1"),
                        tuple("v1:second", "20000002", "SETTLED_NOTIFICATION", "E2E-2", "RJCT", "v1")
                );
        assertThat(captor.getValue())
                .extracting(IncomingNotification::payload)
                .containsExactly(
                        "first-payload".getBytes(StandardCharsets.UTF_8),
                        "second-payload".getBytes(StandardCharsets.UTF_8)
                );
    }

    @Test
    void doesNotPersistPartOfPollWhenARecordCannotBeDecoded() {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        NotificationKafkaConsumer consumer = new NotificationKafkaConsumer(repository);
        ConsumerRecord<String, byte[]> valid = notificationRecord(
                10L,
                "20000001",
                "v1:valid",
                "E2E-1",
                "ACSC",
                "valid-payload"
        );
        ConsumerRecord<String, byte[]> invalid = notificationRecord(
                11L,
                "20000002",
                "v1:invalid",
                "E2E-2",
                "RJCT",
                "invalid-payload"
        );
        invalid.headers().remove("notification.schema-version");

        assertThatThrownBy(() -> consumer.consume(List.of(valid, invalid)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing Kafka header: notification.schema-version");

        verifyNoInteractions(repository);
    }

    private ConsumerRecord<String, byte[]> notificationRecord(
            long offset,
            String ispb,
            String communicationId,
            String paymentId,
            String status,
            String payload
    ) {
        ConsumerRecord<String, byte[]> record = new ConsumerRecord<>(
                "psp-notifications",
                1,
                offset,
                ispb,
                payload.getBytes(StandardCharsets.UTF_8)
        );
        headers(communicationId, paymentId, status).forEach(record.headers()::add);
        return record;
    }

    private RecordHeaders headers(String communicationId, String paymentId, String status) {
        RecordHeaders headers = new RecordHeaders();
        headers.add("notification.communication-id", bytes(communicationId));
        headers.add("notification.event-type", bytes("SETTLED_NOTIFICATION"));
        headers.add("notification.payment-id", bytes(paymentId));
        headers.add("notification.status", bytes(status));
        headers.add("notification.schema-version", bytes("v1"));
        return headers;
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
