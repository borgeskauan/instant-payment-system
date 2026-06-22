package br.kauan.kafkaproducer.kafka;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KafkaPaymentPublisherTest {

    @Test
    void publishesPaymentRequestsToPaymentRequestsTopic() {
        FakeProducerClient paymentProducer = new FakeProducerClient();
        FakeProducerClient statusProducer = new FakeProducerClient();
        KafkaPaymentPublisher publisher = new KafkaPaymentPublisher(paymentProducer, statusProducer);
        byte[] payload = "pacs008".getBytes();

        publisher.publishPaymentRequest(payload).block();

        assertEquals("spi-payment-requests", paymentProducer.sends.getFirst().topic);
        assertArrayEquals(payload, paymentProducer.sends.getFirst().payload);
        assertEquals(0, statusProducer.sends.size());
    }

    @Test
    void publishesStatusReportsToStatusReportsTopic() {
        FakeProducerClient paymentProducer = new FakeProducerClient();
        FakeProducerClient statusProducer = new FakeProducerClient();
        KafkaPaymentPublisher publisher = new KafkaPaymentPublisher(paymentProducer, statusProducer);
        byte[] payload = "pacs002".getBytes();

        publisher.publishStatusReport(payload).block();

        assertEquals("spi-payment-status-reports", statusProducer.sends.getFirst().topic);
        assertArrayEquals(payload, statusProducer.sends.getFirst().payload);
        assertEquals(0, paymentProducer.sends.size());
    }

    @Test
    void propagatesKafkaSendFailures() {
        FakeProducerClient paymentProducer = new FakeProducerClient();
        paymentProducer.failure = new IllegalStateException("send failed");
        KafkaPaymentPublisher publisher = new KafkaPaymentPublisher(paymentProducer, new FakeProducerClient());

        assertThrows(IllegalStateException.class,
                () -> publisher.publishPaymentRequest("pacs008".getBytes()).block());
    }

    @Test
    void warmsUpBothTopicsAndClosesBothProducers() {
        FakeProducerClient paymentProducer = new FakeProducerClient();
        FakeProducerClient statusProducer = new FakeProducerClient();
        KafkaPaymentPublisher publisher = new KafkaPaymentPublisher(paymentProducer, statusProducer);

        publisher.warmUp();
        publisher.close();

        assertEquals(List.of("spi-payment-requests"), paymentProducer.warmedTopics);
        assertEquals(List.of("spi-payment-status-reports"), statusProducer.warmedTopics);
        assertEquals(1, paymentProducer.closeCalls);
        assertEquals(1, statusProducer.closeCalls);
    }

    private static final class FakeProducerClient implements ProducerClient {
        final List<Send> sends = new ArrayList<>();
        final List<String> warmedTopics = new ArrayList<>();
        RuntimeException failure;
        int closeCalls;

        @Override
        public void send(String topic, byte[] payload, SendCallback callback) {
            sends.add(new Send(topic, payload));
            callback.complete(failure);
        }

        @Override
        public void partitionsFor(String topic) {
            warmedTopics.add(topic);
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }

    private record Send(String topic, byte[] payload) {
    }
}
