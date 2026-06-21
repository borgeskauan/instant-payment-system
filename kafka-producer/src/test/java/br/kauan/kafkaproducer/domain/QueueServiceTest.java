package br.kauan.kafkaproducer.domain;

import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueueServiceTest {

    @Test
    void sendPaymentRequestPublishesToPaymentRequestsTopic() {
        KafkaTemplate<String, byte[]> kafkaTemplate = mock(KafkaTemplate.class);
        QueueService queueService = new QueueService(kafkaTemplate);
        byte[] payload = "pacs008".getBytes();
        when(kafkaTemplate.send(eq("spi-payment-requests"), same(payload)))
                .thenReturn(CompletableFuture.completedFuture(sendResult("spi-payment-requests")));

        queueService.sendPaymentRequest(payload).block();

        verify(kafkaTemplate).send("spi-payment-requests", payload);
    }

    @Test
    void sendStatusReportPublishesToStatusReportsTopic() {
        KafkaTemplate<String, byte[]> kafkaTemplate = mock(KafkaTemplate.class);
        QueueService queueService = new QueueService(kafkaTemplate);
        byte[] payload = "pacs002".getBytes();
        when(kafkaTemplate.send(eq("spi-payment-status-reports"), same(payload)))
                .thenReturn(CompletableFuture.completedFuture(sendResult("spi-payment-status-reports")));

        queueService.sendStatusReport(payload).block();

        verify(kafkaTemplate).send("spi-payment-status-reports", payload);
    }

    private SendResult<String, byte[]> sendResult(String topic) {
        RecordMetadata metadata = new RecordMetadata(new TopicPartition(topic, 0), 0, 0, 0, 0, 0);
        return new SendResult<>(null, metadata);
    }
}
