package br.kauan.spi.adapter.output.kafka;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationPublisherTest {

    @Test
    void sendsStoredBytesWithDeterministicTopicKeyAndHeaders() {
        KafkaTemplate<String, byte[]> kafkaTemplate = mock(KafkaTemplate.class);
        NotificationPublisher publisher = new NotificationPublisher(kafkaTemplate);
        CompletableFuture<SendResult<String, byte[]>> brokerConfirmation =
                CompletableFuture.completedFuture(sendResult());
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(brokerConfirmation);
        byte[] storedPayload = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        NotificationPublication notification = NotificationPublication.create(
                "20000001",
                storedPayload,
                "SETTLED_NOTIFICATION",
                "E2E-1",
                "ACSC"
        );

        assertThat(publisher.publish(notification)).isSameAs(brokerConfirmation);

        var captor = forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, byte[]> record = captor.getValue();
        assertThat(record.topic()).isEqualTo("psp-notifications");
        assertThat(record.key()).isEqualTo("20000001");
        assertThat(record.value()).isSameAs(storedPayload);
        assertThat(header(record.headers(), "notification.communication-id"))
                .isEqualTo(notification.communicationId());
        assertThat(header(record.headers(), "notification.event-type"))
                .isEqualTo("SETTLED_NOTIFICATION");
        assertThat(header(record.headers(), "notification.payment-id")).isEqualTo("E2E-1");
        assertThat(header(record.headers(), "notification.schema-version")).isEqualTo("v1");
        assertThat(header(record.headers(), "notification.status")).isEqualTo("ACSC");
        assertThat(record.headers().lastHeader("notification.delivery-id")).isNull();
    }

    @Test
    void omitsStatusHeaderWhenStoredStatusIsAbsent() {
        KafkaTemplate<String, byte[]> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult()));
        NotificationPublisher publisher = new NotificationPublisher(kafkaTemplate);

        publisher.publish(NotificationPublication.create(
                "20000001",
                "{}".getBytes(StandardCharsets.UTF_8),
                "ACCEPTANCE_REQUEST",
                "E2E-1",
                null
        ));

        var captor = forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().headers().lastHeader("notification.status")).isNull();
    }

    private static SendResult<String, byte[]> sendResult() {
        return new SendResult<>(null, new RecordMetadata(
                new TopicPartition("psp-notifications", 0),
                10L,
                0,
                0,
                0,
                0));
    }

    private static String header(Headers headers, String name) {
        return new String(headers.lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
