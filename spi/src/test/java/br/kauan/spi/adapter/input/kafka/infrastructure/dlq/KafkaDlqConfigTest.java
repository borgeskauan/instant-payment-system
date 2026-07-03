package br.kauan.spi.adapter.input.kafka.infrastructure.dlq;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.SendResult;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaDlqConfigTest {

    @Test
    void deadLetterPublishingRecovererPublishesToSourceDlqOnSamePartitionWithSpiHeaders() {
        KafkaDlqConfig config = new KafkaDlqConfig();
        KafkaTemplate<String, byte[]> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        DeadLetterPublishingRecoverer recoverer = config.deadLetterPublishingRecoverer(kafkaTemplate);
        ConsumerRecord<String, byte[]> sourceRecord = new ConsumerRecord<>(
                "spi-payment-requests",
                7,
                99L,
                "payment-key",
                "payload".getBytes(StandardCharsets.UTF_8));

        recoverer.accept(sourceRecord, null, new IllegalStateException("processing failed"));

        var captor = forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        @SuppressWarnings("unchecked")
        ProducerRecord<String, byte[]> dlqRecord = captor.getValue();
        assertThat(dlqRecord.topic()).isEqualTo("spi-payment-requests.dlq");
        assertThat(dlqRecord.partition()).isEqualTo(7);
        assertThat(dlqRecord.value()).isSameAs(sourceRecord.value());
        assertThat(dlqRecord.headers())
                .extracting(header -> header.key(), header -> new String(header.value(), StandardCharsets.UTF_8))
                .contains(
                        tuple("dlq.source-topic", "spi-payment-requests"),
                        tuple("dlq.source-partition", "7"),
                        tuple("dlq.source-offset", "99"),
                        tuple("dlq.service", "spi"),
                        tuple("dlq.error-type", "BATCH_PROCESSING_ERROR"),
                        tuple("dlq.exception-class", IllegalStateException.class.getName()),
                        tuple("dlq.error-message", "processing failed"));
        assertThat(header(dlqRecord.headers(), "dlq.failed-at")).isNotBlank();
        assertThat(header(dlqRecord.headers(), "dlq.stacktrace-short")).contains("processing failed");
    }

    @Test
    void invalidPayloadDeadLetterPublishingRecovererPublishesWithInvalidPayloadErrorType() {
        KafkaDlqConfig config = new KafkaDlqConfig();
        KafkaTemplate<String, byte[]> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        DeadLetterPublishingRecoverer recoverer = config.invalidPayloadDeadLetterPublishingRecoverer(kafkaTemplate);
        ConsumerRecord<String, byte[]> sourceRecord = new ConsumerRecord<>(
                "spi-payment-status-reports",
                5,
                91L,
                "status-key",
                "raw-invalid".getBytes(StandardCharsets.UTF_8));

        recoverer.accept(sourceRecord, null, new IllegalArgumentException("invalid protobuf"));

        var captor = forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        @SuppressWarnings("unchecked")
        ProducerRecord<String, byte[]> dlqRecord = captor.getValue();
        assertThat(dlqRecord.topic()).isEqualTo("spi-payment-status-reports.dlq");
        assertThat(dlqRecord.partition()).isEqualTo(5);
        assertThat(dlqRecord.value()).isSameAs(sourceRecord.value());
        assertThat(header(dlqRecord.headers(), "dlq.error-type")).isEqualTo("INVALID_PAYLOAD");
        assertThat(header(dlqRecord.headers(), "dlq.consumer-group")).isEqualTo("spi-status-report-consumer-group");
    }

    @Test
    void batchLevelRecoveryPublishesEveryRecordIndividuallyToDlq() {
        KafkaDlqConfig config = new KafkaDlqConfig();
        KafkaTemplate<String, byte[]> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        DeadLetterPublishingRecoverer recoverer = config.deadLetterPublishingRecoverer(kafkaTemplate);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(0L, 0L));
        ConsumerRecord<String, byte[]> firstRecord = new ConsumerRecord<>(
                "spi-payment-requests",
                2,
                10L,
                "first-key",
                "first-payload".getBytes(StandardCharsets.UTF_8));
        ConsumerRecord<String, byte[]> secondRecord = new ConsumerRecord<>(
                "spi-payment-requests",
                2,
                11L,
                "second-key",
                "second-payload".getBytes(StandardCharsets.UTF_8));
        TopicPartition topicPartition = new TopicPartition("spi-payment-requests", 2);
        ConsumerRecords<String, byte[]> records = new ConsumerRecords<>(Map.of(
                topicPartition,
                List.of(firstRecord, secondRecord)));

        errorHandler.handleBatch(
                new IllegalStateException("repository failed"),
                records,
                mock(org.apache.kafka.clients.consumer.Consumer.class),
                mock(org.springframework.kafka.listener.MessageListenerContainer.class),
                () -> {
                });

        var captor = forClass(ProducerRecord.class);
        verify(kafkaTemplate, org.mockito.Mockito.times(2)).send(captor.capture());
        @SuppressWarnings("unchecked")
        List<ProducerRecord<String, byte[]>> dlqRecords =
                (List<ProducerRecord<String, byte[]>>) (List<?>) captor.getAllValues();
        assertThat(dlqRecords)
                .extracting(ProducerRecord::topic, ProducerRecord::partition, ProducerRecord::key, ProducerRecord::value)
                .containsExactly(
                        tuple("spi-payment-requests.dlq", 2, "first-key", firstRecord.value()),
                        tuple("spi-payment-requests.dlq", 2, "second-key", secondRecord.value()));
        assertThat(dlqRecords)
                .allSatisfy(dlqRecord ->
                        assertThat(header(dlqRecord.headers(), "dlq.error-type"))
                                .isEqualTo("BATCH_PROCESSING_ERROR"));
    }

    private static String header(Headers headers, String name) {
        return new String(headers.lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
