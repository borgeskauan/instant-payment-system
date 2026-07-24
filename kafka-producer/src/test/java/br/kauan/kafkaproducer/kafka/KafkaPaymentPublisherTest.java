package br.kauan.kafkaproducer.kafka;

import br.kauan.kafkaproducer.security.PspAuthorizationException;
import br.kauan.pix.internal.v1.PaymentRequest;
import br.kauan.pix.internal.v1.PaymentStatus;
import br.kauan.pix.internal.v1.PaymentStatusReport;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KafkaPaymentPublisherTest {

    @Test
    void publishesPaymentRequestsToPaymentRequestsTopic() throws Exception {
        FakeProducerClient paymentProducer = new FakeProducerClient();
        FakeProducerClient statusProducer = new FakeProducerClient();
        KafkaPaymentPublisher publisher = new KafkaPaymentPublisher(paymentProducer, statusProducer);

        publisher.publishPaymentRequest("10000001", pacs008()).block();

        ProducerRecord<byte[], byte[]> record = paymentProducer.sends.getFirst();
        assertEquals("spi-payment-requests", record.topic());
        assertAuthenticatedIspb(record, "10000001");
        PaymentRequest request = PaymentRequest.parseFrom(record.value());
        assertEquals("E2E-1", request.getPaymentId());
        assertEquals(1234L, request.getAmountCents());
        assertEquals("BRL", request.getCurrency());
        assertEquals("000123", request.getSender().getAccount().getNumber());
        assertEquals("0012", request.getSender().getAccount().getBranch());
        assertEquals("10000001", request.getSender().getAccount().getIspb());
        assertEquals("+5511999999999", request.getReceiver().getPixKey());
        assertEquals("20000001", request.getReceiver().getAccount().getIspb());
        assertEquals(0, statusProducer.sends.size());
    }

    @Test
    void publishesStatusReportsToStatusReportsTopic() throws Exception {
        FakeProducerClient paymentProducer = new FakeProducerClient();
        FakeProducerClient statusProducer = new FakeProducerClient();
        KafkaPaymentPublisher publisher = new KafkaPaymentPublisher(paymentProducer, statusProducer);

        publisher.publishStatusReport("20000001", pacs002("ACSP")).block();

        ProducerRecord<byte[], byte[]> record = statusProducer.sends.getFirst();
        assertEquals("spi-payment-status-reports", record.topic());
        assertAuthenticatedIspb(record, "20000001");
        PaymentStatusReport report = PaymentStatusReport.parseFrom(record.value());
        assertEquals("E2E-1", report.getPaymentId());
        assertEquals(PaymentStatus.ACCEPTED_IN_PROCESS, report.getStatus());
        assertEquals(0, paymentProducer.sends.size());
    }

    @Test
    void mapsRejectedStatusReports() throws Exception {
        FakeProducerClient statusProducer = new FakeProducerClient();
        KafkaPaymentPublisher publisher = new KafkaPaymentPublisher(new FakeProducerClient(), statusProducer);

        publisher.publishStatusReport("20000001", pacs002("RJCT")).block();

        PaymentStatusReport report = PaymentStatusReport.parseFrom(statusProducer.sends.getFirst().value());
        assertEquals(PaymentStatus.REJECTED, report.getStatus());
    }

    @Test
    void publishesOnePaymentRequestRecordPerPacs008Transaction() throws Exception {
        FakeProducerClient paymentProducer = new FakeProducerClient();
        KafkaPaymentPublisher publisher = new KafkaPaymentPublisher(paymentProducer, new FakeProducerClient());

        publisher.publishPaymentRequest("10000001", pacs008Multi()).block();

        assertEquals(2, paymentProducer.sends.size());
        assertEquals("E2E-1", PaymentRequest.parseFrom(paymentProducer.sends.get(0).value()).getPaymentId());
        assertEquals("E2E-2", PaymentRequest.parseFrom(paymentProducer.sends.get(1).value()).getPaymentId());
        assertAuthenticatedIspb(paymentProducer.sends.get(0), "10000001");
        assertAuthenticatedIspb(paymentProducer.sends.get(1), "10000001");
    }

    @Test
    void rejectsEntirePaymentRequestWhenOneTransactionBelongsToAnotherPsp() {
        FakeProducerClient paymentProducer = new FakeProducerClient();
        KafkaPaymentPublisher publisher = new KafkaPaymentPublisher(paymentProducer, new FakeProducerClient());
        byte[] payload = new String(pacs008Multi(), StandardCharsets.UTF_8)
                .replaceFirst("10000001", "99999999")
                .getBytes(StandardCharsets.UTF_8);

        assertThrows(PspAuthorizationException.class,
                () -> publisher.publishPaymentRequest("10000001", payload).block());

        assertEquals(0, paymentProducer.sends.size());
    }

    @Test
    void publishesOneStatusReportRecordPerPacs002Transaction() throws Exception {
        FakeProducerClient statusProducer = new FakeProducerClient();
        KafkaPaymentPublisher publisher = new KafkaPaymentPublisher(new FakeProducerClient(), statusProducer);

        publisher.publishStatusReport("20000001", pacs002Multi()).block();

        assertEquals(2, statusProducer.sends.size());
        assertEquals("E2E-1", PaymentStatusReport.parseFrom(statusProducer.sends.get(0).value()).getPaymentId());
        assertEquals("E2E-2", PaymentStatusReport.parseFrom(statusProducer.sends.get(1).value()).getPaymentId());
        assertAuthenticatedIspb(statusProducer.sends.get(0), "20000001");
        assertAuthenticatedIspb(statusProducer.sends.get(1), "20000001");
    }

    @Test
    void propagatesKafkaSendFailures() {
        FakeProducerClient paymentProducer = new FakeProducerClient();
        paymentProducer.failure = new IllegalStateException("send failed");
        KafkaPaymentPublisher publisher = new KafkaPaymentPublisher(paymentProducer, new FakeProducerClient());

        assertThrows(IllegalStateException.class,
                () -> publisher.publishPaymentRequest("10000001", pacs008()).block());
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

    private static void assertAuthenticatedIspb(ProducerRecord<byte[], byte[]> record, String expectedIspb) {
        List<Header> headers = new ArrayList<>();
        record.headers().headers(KafkaPaymentPublisher.AUTHENTICATED_ISPB_HEADER).forEach(headers::add);
        assertEquals(1, headers.size());
        assertEquals(expectedIspb, new String(headers.getFirst().value(), StandardCharsets.UTF_8));
    }

    private static final class FakeProducerClient implements ProducerClient {
        final List<ProducerRecord<byte[], byte[]>> sends = new ArrayList<>();
        final List<String> warmedTopics = new ArrayList<>();
        RuntimeException failure;
        int closeCalls;

        @Override
        public void send(ProducerRecord<byte[], byte[]> record, SendCallback callback) {
            sends.add(record);
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

    private static byte[] pacs008() {
        return """
                {"GrpHdr":{"MsgId":"MSG-1","CreDtTm":"2026-06-23T20:00:01.123Z","NbOfTxs":1},"CdtTrfTxInf":[{"PmtId":{"EndToEndId":"E2E-1"},"IntrBkSttlmAmt":{"value":12.34,"Ccy":"BRL"},"Dbtr":{"Nm":"Sender","Id":{"PrvtId":{"Othr":{"Id":"12345678900"}}}},"DbtrAcct":{"Id":{"Othr":{"Id":"000123","Issr":"0012"}},"Tp":{"Cd":"CACC"}},"DbtrAgt":{"FinInstnId":{"ClrSysMmbId":{"MmbId":"10000001"}}},"CdtrAgt":{"FinInstnId":{"ClrSysMmbId":{"MmbId":"20000001"}}},"Cdtr":{"Nm":"Receiver","Id":{"PrvtId":{"Othr":{"Id":"98765432100"}}}},"CdtrAcct":{"Id":{"Othr":{"Id":"000456","Issr":"0034"}},"Tp":{"Cd":"CACC"},"Prxy":{"Id":"+5511999999999"}},"RmtInf":{"Ustrd":"Load test payment"}}]}
                """.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] pacs008Multi() {
        return """
                {"GrpHdr":{"MsgId":"MSG-1","CreDtTm":"2026-06-23T20:00:01.123Z","NbOfTxs":2},"CdtTrfTxInf":[
                {"PmtId":{"EndToEndId":"E2E-1"},"IntrBkSttlmAmt":{"value":12.34,"Ccy":"BRL"},"Dbtr":{"Nm":"Sender","Id":{"PrvtId":{"Othr":{"Id":"12345678900"}}}},"DbtrAcct":{"Id":{"Othr":{"Id":"000123","Issr":"0012"}},"Tp":{"Cd":"CACC"}},"DbtrAgt":{"FinInstnId":{"ClrSysMmbId":{"MmbId":"10000001"}}},"CdtrAgt":{"FinInstnId":{"ClrSysMmbId":{"MmbId":"20000001"}}},"Cdtr":{"Nm":"Receiver","Id":{"PrvtId":{"Othr":{"Id":"98765432100"}}}},"CdtrAcct":{"Id":{"Othr":{"Id":"000456","Issr":"0034"}},"Tp":{"Cd":"CACC"},"Prxy":{"Id":"+5511999999999"}},"RmtInf":{"Ustrd":"Load test payment"}},
                {"PmtId":{"EndToEndId":"E2E-2"},"IntrBkSttlmAmt":{"value":56.78,"Ccy":"BRL"},"Dbtr":{"Nm":"Sender","Id":{"PrvtId":{"Othr":{"Id":"12345678900"}}}},"DbtrAcct":{"Id":{"Othr":{"Id":"000124","Issr":"0012"}},"Tp":{"Cd":"CACC"}},"DbtrAgt":{"FinInstnId":{"ClrSysMmbId":{"MmbId":"10000001"}}},"CdtrAgt":{"FinInstnId":{"ClrSysMmbId":{"MmbId":"20000001"}}},"Cdtr":{"Nm":"Receiver","Id":{"PrvtId":{"Othr":{"Id":"98765432100"}}}},"CdtrAcct":{"Id":{"Othr":{"Id":"000457","Issr":"0034"}},"Tp":{"Cd":"CACC"},"Prxy":{"Id":"+5511999999999"}},"RmtInf":{"Ustrd":"Load test payment"}}
                ]}
                """.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] pacs002(String status) {
        return """
                {"GrpHdr":{"MsgId":"STATUS-E2E-1","CreDtTm":"2026-06-23T20:00:01.123Z","NbOfTxs":1},"TxInfAndSts":[{"OrgnlEndToEndId":"E2E-1","TxSts":"%s"}]}
                """.formatted(status).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] pacs002Multi() {
        return """
                {"GrpHdr":{"MsgId":"STATUS-MULTI","CreDtTm":"2026-06-23T20:00:01.123Z","NbOfTxs":2},"TxInfAndSts":[{"OrgnlEndToEndId":"E2E-1","TxSts":"ACSP"},{"OrgnlEndToEndId":"E2E-2","TxSts":"RJCT"}]}
                """.getBytes(StandardCharsets.UTF_8);
    }
}
