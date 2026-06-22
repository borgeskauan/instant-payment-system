package br.kauan.kafkaproducer.kafka;

public interface ProducerClient extends AutoCloseable {

    void send(String topic, byte[] payload, SendCallback callback);

    void partitionsFor(String topic);

    @Override
    void close();
}
