package br.kauan.kafkaproducer.kafka;

@FunctionalInterface
public interface SendCallback {

    void complete(RuntimeException failure);
}
