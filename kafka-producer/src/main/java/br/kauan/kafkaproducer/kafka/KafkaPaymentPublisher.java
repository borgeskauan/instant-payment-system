package br.kauan.kafkaproducer.kafka;

import br.kauan.kafkaproducer.config.AppConfig;

import reactor.core.publisher.Mono;

public class KafkaPaymentPublisher implements PaymentPublisher {

    private final ProducerClient paymentRequestProducer;
    private final ProducerClient statusReportProducer;

    public KafkaPaymentPublisher(ProducerClient paymentRequestProducer, ProducerClient statusReportProducer) {
        this.paymentRequestProducer = paymentRequestProducer;
        this.statusReportProducer = statusReportProducer;
    }

    public static KafkaPaymentPublisher fromConfig(AppConfig config) {
        return new KafkaPaymentPublisher(
                new KafkaProducerClient(config.producerProperties()),
                new KafkaProducerClient(config.producerProperties()));
    }

    @Override
    public Mono<Void> publishPaymentRequest(byte[] payload) {
        return publish(paymentRequestProducer, AppConfig.PAYMENT_REQUESTS_TOPIC, payload);
    }

    @Override
    public Mono<Void> publishStatusReport(byte[] payload) {
        return publish(statusReportProducer, AppConfig.PAYMENT_STATUS_REPORTS_TOPIC, payload);
    }

    @Override
    public void warmUp() {
        paymentRequestProducer.partitionsFor(AppConfig.PAYMENT_REQUESTS_TOPIC);
        statusReportProducer.partitionsFor(AppConfig.PAYMENT_STATUS_REPORTS_TOPIC);
    }

    @Override
    public void close() {
        paymentRequestProducer.close();
        statusReportProducer.close();
    }

    private Mono<Void> publish(ProducerClient producer, String topic, byte[] payload) {
        return Mono.create(sink -> producer.send(topic, payload, failure -> {
            if (failure == null) {
                sink.success();
            } else {
                sink.error(failure);
            }
        }));
    }
}
