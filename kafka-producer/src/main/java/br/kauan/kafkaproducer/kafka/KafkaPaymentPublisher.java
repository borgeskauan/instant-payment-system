package br.kauan.kafkaproducer.kafka;

import br.kauan.kafkaproducer.config.AppConfig;
import br.kauan.kafkaproducer.pacs.PacsToInternalMessageMapper;

import br.kauan.pix.internal.v1.PaymentRequest;
import br.kauan.pix.internal.v1.PaymentStatusReport;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Mono;

public class KafkaPaymentPublisher implements PaymentPublisher {

    private final ProducerClient paymentRequestProducer;
    private final ProducerClient statusReportProducer;
    private final PacsToInternalMessageMapper messageMapper;

    public KafkaPaymentPublisher(ProducerClient paymentRequestProducer, ProducerClient statusReportProducer) {
        this(paymentRequestProducer, statusReportProducer, new PacsToInternalMessageMapper());
    }

    KafkaPaymentPublisher(
            ProducerClient paymentRequestProducer,
            ProducerClient statusReportProducer,
            PacsToInternalMessageMapper messageMapper
    ) {
        this.paymentRequestProducer = paymentRequestProducer;
        this.statusReportProducer = statusReportProducer;
        this.messageMapper = messageMapper;
    }

    public static KafkaPaymentPublisher fromConfig(AppConfig config) {
        return new KafkaPaymentPublisher(
                new KafkaProducerClient(config.producerProperties()),
                new KafkaProducerClient(config.producerProperties()));
    }

    @Override
    public Mono<Void> publishPaymentRequest(byte[] payload) {
        return Mono.defer(() -> {
            List<PaymentRequest> requests = messageMapper.toPaymentRequests(payload);
            List<Mono<Void>> sends = new ArrayList<>(requests.size());
            for (PaymentRequest request : requests) {
                sends.add(publish(
                        paymentRequestProducer,
                        AppConfig.PAYMENT_REQUESTS_TOPIC,
                        request.toByteArray()));
            }
            return Mono.when(sends);
        });
    }

    @Override
    public Mono<Void> publishStatusReport(byte[] payload) {
        return Mono.defer(() -> {
            List<PaymentStatusReport> reports = messageMapper.toPaymentStatusReports(payload);
            List<Mono<Void>> sends = new ArrayList<>(reports.size());
            for (PaymentStatusReport report : reports) {
                sends.add(publish(
                        statusReportProducer,
                        AppConfig.PAYMENT_STATUS_REPORTS_TOPIC,
                        report.toByteArray()));
            }
            return Mono.when(sends);
        });
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
