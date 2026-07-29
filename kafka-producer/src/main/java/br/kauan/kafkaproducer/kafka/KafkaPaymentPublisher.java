package br.kauan.kafkaproducer.kafka;

import br.kauan.kafkaproducer.config.AppConfig;
import br.kauan.kafkaproducer.pacs.PacsToInternalMessageMapper;
import br.kauan.kafkaproducer.security.PspAuthorizationException;

import br.kauan.pix.internal.v1.PaymentRequest;
import br.kauan.pix.internal.v1.PaymentStatusReport;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Mono;

public class KafkaPaymentPublisher implements PaymentPublisher {

    public static final String AUTHENTICATED_ISPB_HEADER = "authenticated-ispb";

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
    public Mono<Void> publishPaymentRequest(String authenticatedIspb, byte[] payload) {
        return Mono.defer(() -> {
            List<PaymentRequest> requests = messageMapper.toPaymentRequests(payload);
            authorizePaymentRequests(authenticatedIspb, requests);
            List<Mono<Void>> sends = new ArrayList<>(requests.size());
            for (PaymentRequest request : requests) {
                sends.add(publish(
                        paymentRequestProducer,
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
                        statusReportProducer,
                        AppConfig.PAYMENT_STATUS_REPORTS_TOPIC,
                        authenticatedIspb,
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

    private void authorizePaymentRequests(String authenticatedIspb, List<PaymentRequest> requests) {
        for (PaymentRequest request : requests) {
            String senderIspb = request.getSender().getAccount().getIspb();
            if (!authenticatedIspb.equals(senderIspb)) {
                throw new PspAuthorizationException(
                        "authenticated PSP cannot send payment request " + request.getPaymentId());
            }
        }
    }

    private Mono<Void> publish(
            ProducerClient producer,
            String topic,
            String authenticatedIspb,
        byte[] payload
    ) {
        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(topic, payload);
        record.headers().add(
                AUTHENTICATED_ISPB_HEADER,
                authenticatedIspb.getBytes(StandardCharsets.UTF_8));
        return Mono.create(sink -> producer.send(record, failure -> {
            if (failure == null) {
                sink.success();
            } else {
                sink.error(failure);
            }
        }));
    }
}
