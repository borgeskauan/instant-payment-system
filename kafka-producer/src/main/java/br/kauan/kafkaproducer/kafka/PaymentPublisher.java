package br.kauan.kafkaproducer.kafka;

import reactor.core.publisher.Mono;

public interface PaymentPublisher extends AutoCloseable {

    Mono<Void> publishPaymentRequest(byte[] payload);

    Mono<Void> publishStatusReport(byte[] payload);

    void warmUp();

    @Override
    void close();
}
