package br.kauan.spi.adapter.output.kafka;

import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationPublisherTest {

    @Test
    void publishNotificationsStartsAllSendsBeforeWaitingForBrokerConfirmations() throws Exception {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        NotificationPublisher publisher = new NotificationPublisher(kafkaTemplate);
        CompletableFuture<SendResult<String, String>> firstFuture = new CompletableFuture<>();
        CompletableFuture<SendResult<String, String>> secondFuture = new CompletableFuture<>();
        when(kafkaTemplate.send("psp-notifications", "20000001", "{\"a\":1}")).thenReturn(firstFuture);
        when(kafkaTemplate.send("psp-notifications", "20000002", "{\"b\":2}")).thenReturn(secondFuture);

        Map<String, String> notifications = new LinkedHashMap<>();
        notifications.put("20000001", "{\"a\":1}");
        notifications.put("20000002", "{\"b\":2}");
        CompletableFuture<Void> publishing =
                CompletableFuture.runAsync(() -> publisher.publishNotifications(notifications));

        waitUntilBothSendsStarted(kafkaTemplate);

        firstFuture.complete(sendResult(0, 10L));
        secondFuture.complete(sendResult(1, 11L));
        publishing.get(2, TimeUnit.SECONDS);
    }

    @Test
    void publishNotificationsPropagatesAnyBrokerFailure() {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        NotificationPublisher publisher = new NotificationPublisher(kafkaTemplate);
        CompletableFuture<SendResult<String, String>> firstFuture = CompletableFuture.completedFuture(sendResult(0, 10L));
        CompletableFuture<SendResult<String, String>> secondFuture = new CompletableFuture<>();
        secondFuture.completeExceptionally(new IllegalStateException("broker rejected"));
        when(kafkaTemplate.send("psp-notifications", "20000001", "{\"a\":1}")).thenReturn(firstFuture);
        when(kafkaTemplate.send("psp-notifications", "20000002", "{\"b\":2}")).thenReturn(secondFuture);

        Map<String, String> notifications = new LinkedHashMap<>();
        notifications.put("20000001", "{\"a\":1}");
        notifications.put("20000002", "{\"b\":2}");
        assertThatThrownBy(() -> publisher.publishNotifications(notifications))
                .hasMessageContaining("Failed to publish notification");
    }

    private static void waitUntilBothSendsStarted(KafkaTemplate<String, String> kafkaTemplate)
            throws InterruptedException {
        AssertionError lastFailure = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            try {
                verify(kafkaTemplate).send("psp-notifications", "20000001", "{\"a\":1}");
                verify(kafkaTemplate).send("psp-notifications", "20000002", "{\"b\":2}");
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
}
