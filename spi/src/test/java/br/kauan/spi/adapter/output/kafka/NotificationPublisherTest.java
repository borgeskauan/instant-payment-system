package br.kauan.spi.adapter.output.kafka;

import br.kauan.spi.application.notification.OutboundNotification;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationPublisherTest {

    @Test
    void sendsStoredBytesWithRecipientKeyAndMessageIdentityOnly() {
        KafkaTemplate<String, byte[]> kafkaTemplate = mock(KafkaTemplate.class);
        NotificationPublisher publisher = new NotificationPublisher(kafkaTemplate);
        CompletableFuture<SendResult<String, byte[]>> brokerConfirmation =
                CompletableFuture.completedFuture(sendResult());
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(brokerConfirmation);
        byte[] storedPayload = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        OutboundNotification notification = OutboundNotification.create(
                "20000001",
                storedPayload,
                "message-1"
        );

        assertThat(publisher.publishAll(List.of(notification))).succeedsWithin(java.time.Duration.ofSeconds(1));

        var captor = forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, byte[]> record = captor.getValue();
        assertThat(record.topic()).isEqualTo("psp-notifications-v1");
        assertThat(record.key()).isEqualTo("20000001");
        assertThat(record.value()).isSameAs(storedPayload);
        assertThat(header(record.headers(), "notification.communication-id"))
                .isEqualTo(notification.communicationId());
        assertThat(record.headers().toArray()).hasSize(1);
        assertThat(record.headers().lastHeader("notification.delivery-id")).isNull();
    }

    private static SendResult<String, byte[]> sendResult() {
        return new SendResult<>(null, new RecordMetadata(
                new TopicPartition("psp-notifications-v1", 0),
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
