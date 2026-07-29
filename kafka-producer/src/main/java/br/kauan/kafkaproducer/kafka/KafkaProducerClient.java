package br.kauan.kafkaproducer.kafka;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.time.Duration;
import java.util.Properties;

public class KafkaProducerClient implements ProducerClient {

    private final KafkaProducer<byte[], byte[]> producer;

    public KafkaProducerClient(Properties properties) {
        this.producer = new KafkaProducer<>(properties);
    }

    @Override
    public void send(ProducerRecord<byte[], byte[]> record, SendCallback callback) {
        producer.send(record, (metadata, exception) -> {
            if (exception == null) {
                callback.complete(null);
                return;
            }
            if (exception instanceof RuntimeException runtimeException) {
                callback.complete(runtimeException);
                return;
            }
            callback.complete(new IllegalStateException("Kafka send failed", exception));
        });
    }

    @Override
    public void partitionsFor(String topic) {
        producer.partitionsFor(topic);
    }

    @Override
    public void close() {
        producer.flush();
        producer.close(Duration.ofSeconds(5));
    }
}
