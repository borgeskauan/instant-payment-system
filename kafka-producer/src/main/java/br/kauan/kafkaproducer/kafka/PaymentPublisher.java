package br.kauan.kafkaproducer.kafka;

import reactor.core.publisher.Mono;

public interface PaymentPublisher extends AutoCloseable {

    Mono<Void> publishPaymentRequest(String authenticatedIspb, byte[] payload);

    Mono<Void> publishStatusReport(String authenticatedIspb, byte[] payload);

    void warmUp();

    @Override
    void close();
}
