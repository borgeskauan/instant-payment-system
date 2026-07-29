package br.kauan.kafkaproducer.kafka;

import org.apache.kafka.clients.producer.ProducerRecord;

public interface ProducerClient extends AutoCloseable {

    void send(ProducerRecord<byte[], byte[]> record, SendCallback callback);

    void partitionsFor(String topic);

    @Override
    void close();
}
