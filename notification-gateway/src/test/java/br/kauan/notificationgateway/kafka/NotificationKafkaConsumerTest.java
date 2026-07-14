package br.kauan.notificationgateway.kafka;

import br.kauan.notificationgateway.delivery.IncomingNotification;
import br.kauan.notificationgateway.delivery.NotificationDeliveryRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NotificationKafkaConsumerTest {

    @Test
    void consumesKafkaRecordAsPersistentDelivery() {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        NotificationKafkaConsumer consumer = new NotificationKafkaConsumer(repository);
        ConsumerRecord<String, byte[]> record = new ConsumerRecord<>(
                "psp-notifications",
                1,
                10L,
                "20000001",
                "payload".getBytes(StandardCharsets.UTF_8)
        );
        headers().forEach(record.headers()::add);

        consumer.consume(record);

        var captor = ArgumentCaptor.forClass(IncomingNotification.class);
        verify(repository).saveIfAbsent(captor.capture());
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
                        "v1:abc",
                        "20000001",
                        "SETTLED_NOTIFICATION",
                        "E2E-1",
                        "ACSC",
                        "v1"
                );
        assertThat(captor.getValue().payload()).isEqualTo("payload".getBytes(StandardCharsets.UTF_8));
    }

    private RecordHeaders headers() {
        RecordHeaders headers = new RecordHeaders();
        headers.add("notification.communication-id", bytes("v1:abc"));
        headers.add("notification.event-type", bytes("SETTLED_NOTIFICATION"));
        headers.add("notification.payment-id", bytes("E2E-1"));
        headers.add("notification.status", bytes("ACSC"));
        headers.add("notification.schema-version", bytes("v1"));
        return headers;
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
