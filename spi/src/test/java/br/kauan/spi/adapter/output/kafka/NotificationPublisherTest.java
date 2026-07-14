package br.kauan.spi.adapter.output.kafka;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationPublisherTest {

    @Test
    void publishNotificationsStartsAllSendsBeforeWaitingForBrokerConfirmations() throws Exception {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        NotificationPublisher publisher = new NotificationPublisher(kafkaTemplate);
        CompletableFuture<SendResult<String, String>> firstFuture = new CompletableFuture<>();
        CompletableFuture<SendResult<String, String>> secondFuture = new CompletableFuture<>();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(firstFuture, secondFuture);

        List<NotificationPublication> notifications = List.of(
                NotificationPublication.create("20000001", "{\"a\":1}", "ACCEPTANCE_REQUEST", "E2E-1", null),
                NotificationPublication.create("20000001", "{\"b\":2}", "SETTLED_NOTIFICATION", "E2E-2", "ACSC")
        );
        CompletableFuture<Void> publishing =
                CompletableFuture.runAsync(() -> publisher.publishNotifications(notifications));

        waitUntilBothSendsStarted(kafkaTemplate);

        firstFuture.complete(sendResult(0, 10L));
        secondFuture.complete(sendResult(1, 11L));
        publishing.get(2, TimeUnit.SECONDS);

        var captor = forClass(ProducerRecord.class);
        verify(kafkaTemplate, times(2)).send(captor.capture());
        List<ProducerRecord<String, String>> records = (List<ProducerRecord<String, String>>) (List<?>) captor.getAllValues();
        assertThat(records)
                .extracting(ProducerRecord::topic, ProducerRecord::key, ProducerRecord::value)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("psp-notifications", "20000001", "{\"a\":1}"),
                        org.assertj.core.groups.Tuple.tuple("psp-notifications", "20000001", "{\"b\":2}")
                );
        assertThat(header(records.getFirst().headers(), "notification.communication-id"))
                .startsWith("v1:");
        assertThat(header(records.getFirst().headers(), "notification.event-type"))
                .isEqualTo("ACCEPTANCE_REQUEST");
        assertThat(header(records.getFirst().headers(), "notification.payment-id"))
                .isEqualTo("E2E-1");
        assertThat(header(records.getFirst().headers(), "notification.schema-version"))
                .isEqualTo("v1");
        assertThat(records.getFirst().headers().lastHeader("notification.status")).isNull();
        assertThat(records.getFirst().headers().lastHeader("notification.delivery-id")).isNull();
        assertThat(header(records.get(1).headers(), "notification.status"))
                .isEqualTo("ACSC");
    }

    @Test
    void publishNotificationsPropagatesAnyBrokerFailure() {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        NotificationPublisher publisher = new NotificationPublisher(kafkaTemplate);
        CompletableFuture<SendResult<String, String>> firstFuture = CompletableFuture.completedFuture(sendResult(0, 10L));
        CompletableFuture<SendResult<String, String>> secondFuture = new CompletableFuture<>();
        secondFuture.completeExceptionally(new IllegalStateException("broker rejected"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(firstFuture, secondFuture);

        List<NotificationPublication> notifications = List.of(
                NotificationPublication.create("20000001", "{\"a\":1}", "ACCEPTANCE_REQUEST", "E2E-1", null),
                NotificationPublication.create("20000001", "{\"b\":2}", "SETTLED_NOTIFICATION", "E2E-2", "ACSC")
        );
        assertThatThrownBy(() -> publisher.publishNotifications(notifications))
                .hasMessageContaining("Failed to publish notification");
    }

    private static void waitUntilBothSendsStarted(KafkaTemplate<String, String> kafkaTemplate)
            throws InterruptedException {
        AssertionError lastFailure = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            try {
                verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
                return;
            } catch (AssertionError failure) {
                lastFailure = failure;
                Thread.sleep(10);
            }
        }
        throw lastFailure;
    }

    private static SendResult<String, String> sendResult(int partition, long offset) {
        return new SendResult<>(null, new RecordMetadata(
                new TopicPartition("psp-notifications", partition),
                offset,
                0,
                0,
                0,
                0));
    }

    private static String header(Headers headers, String name) {
        return new String(headers.lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
