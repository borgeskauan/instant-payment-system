package br.kauan.kafkaproducer.kafka;

import br.kauan.kafkaproducer.config.AppConfig;
import br.kauan.kafkaproducer.pacs.PacsToInternalMessageMapper;

import br.kauan.pix.internal.v1.PaymentRequest;
import br.kauan.pix.internal.v1.PaymentStatusReport;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Mono;

public class KafkaPaymentPublisher implements PaymentPublisher {

    public static final String AUTHENTICATED_ISPB_HEADER = "authenticated-ispb";

    private final ProducerClient producer;
    private final PacsToInternalMessageMapper messageMapper;

    public KafkaPaymentPublisher(ProducerClient producer) {
        this(producer, new PacsToInternalMessageMapper());
    }

    KafkaPaymentPublisher(
            ProducerClient producer,
            PacsToInternalMessageMapper messageMapper
    ) {
        this.producer = producer;
        this.messageMapper = messageMapper;
    }

    public static KafkaPaymentPublisher fromConfig(AppConfig config) {
        return new KafkaPaymentPublisher(new KafkaProducerClient(config.producerProperties()));
    }

    @Override
    public Mono<Void> publishPaymentRequest(String authenticatedIspb, byte[] payload) {
        return Mono.defer(() -> {
            List<PaymentRequest> requests = messageMapper.toPaymentRequests(payload);
            List<Mono<Void>> sends = new ArrayList<>(requests.size());
            for (PaymentRequest request : requests) {
                sends.add(publish(
                        AppConfig.PAYMENT_REQUESTS_TOPIC,
                        authenticatedIspb,
                        request.toByteArray()));
            }
            return Mono.when(sends);
        });
    }

    @Override
    public Mono<Void> publishStatusReport(String authenticatedIspb, byte[] payload) {
        return Mono.defer(() -> {
            List<PaymentStatusReport> reports = messageMapper.toPaymentStatusReports(payload);
            List<Mono<Void>> sends = new ArrayList<>(reports.size());
            for (PaymentStatusReport report : reports) {
                sends.add(publish(
                        AppConfig.PAYMENT_STATUS_REPORTS_TOPIC,
                        authenticatedIspb,
                        report.toByteArray()));
            }
            return Mono.when(sends);
        });
    }

    @Override
    public void warmUp() {
        producer.partitionsFor(AppConfig.PAYMENT_REQUESTS_TOPIC);
        producer.partitionsFor(AppConfig.PAYMENT_STATUS_REPORTS_TOPIC);
    }

    @Override
    public void close() {
        producer.close();
    }

    private Mono<Void> publish(
            String topic,
            String authenticatedIspb,
            byte[] payload
    ) {
        byte[] ispbKey = authenticatedIspb.getBytes(StandardCharsets.UTF_8);
        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(topic, ispbKey, payload);
        record.headers().add(
                AUTHENTICATED_ISPB_HEADER,
                ispbKey);
        return Mono.create(sink -> producer.send(record, failure -> {
            if (failure == null) {
                sink.success();
            } else {
                sink.error(failure);
            }
        }));
    }
}
