package br.kauan.kafkaproducer.kafka;

import br.kauan.kafkaproducer.pacs.InvalidPacsPayloadException;
import br.kauan.pix.internal.v1.PaymentRequest;
import br.kauan.pix.internal.v1.PaymentStatus;
import br.kauan.pix.internal.v1.PaymentStatusReport;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KafkaPaymentPublisherTest {

    @Test
    void publishesPaymentRequestsToPaymentRequestsTopic() throws Exception {
        FakeProducerClient producer = new FakeProducerClient();
        KafkaPaymentPublisher publisher = new KafkaPaymentPublisher(producer);

        publisher.publishPaymentRequest("10000001", pacs008()).block();

        ProducerRecord<byte[], byte[]> record = producer.sends.getFirst();
        assertEquals("spi-payment-requests", record.topic());
        assertRecordKey(record, "10000001");
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
        assertEquals(1, producer.sends.size());
    }

    @Test
    void publishesStatusReportsToStatusReportsTopic() throws Exception {
        FakeProducerClient producer = new FakeProducerClient();
        KafkaPaymentPublisher publisher = new KafkaPaymentPublisher(producer);

        publisher.publishStatusReport("20000001", pacs002("ACSP")).block();

        ProducerRecord<byte[], byte[]> record = producer.sends.getFirst();
        assertEquals("spi-payment-status-reports", record.topic());
        assertRecordKey(record, "20000001");
        assertAuthenticatedIspb(record, "20000001");
        PaymentStatusReport report = PaymentStatusReport.parseFrom(record.value());
        assertEquals("E2E-1", report.getPaymentId());
        assertEquals(PaymentStatus.ACCEPTED_IN_PROCESS, report.getStatus());
        assertEquals(1, producer.sends.size());
    }

    @Test
    void mapsRejectedStatusReports() throws Exception {
        FakeProducerClient producer = new FakeProducerClient();
        KafkaPaymentPublisher publisher = new KafkaPaymentPublisher(producer);

        publisher.publishStatusReport("20000001", pacs002("RJCT")).block();

        PaymentStatusReport report = PaymentStatusReport.parseFrom(producer.sends.getFirst().value());
        assertEquals(PaymentStatus.REJECTED, report.getStatus());
    }

    @Test
    void publishesOnePaymentRequestRecordPerPacs008Transaction() throws Exception {
        FakeProducerClient producer = new FakeProducerClient();
        KafkaPaymentPublisher publisher = new KafkaPaymentPublisher(producer);

        publisher.publishPaymentRequest("10000001", pacs008Multi()).block();

        assertEquals(2, producer.sends.size());
        assertEquals("E2E-1", PaymentRequest.parseFrom(producer.sends.get(0).value()).getPaymentId());
        assertEquals("E2E-2", PaymentRequest.parseFrom(producer.sends.get(1).value()).getPaymentId());
        assertRecordKey(producer.sends.get(0), "10000001");
        assertRecordKey(producer.sends.get(1), "10000001");
        assertAuthenticatedIspb(producer.sends.get(0), "10000001");
        assertAuthenticatedIspb(producer.sends.get(1), "10000001");
    }

    @Test
    void publishesOneStatusReportRecordPerPacs002Transaction() throws Exception {
        FakeProducerClient producer = new FakeProducerClient();
        KafkaPaymentPublisher publisher = new KafkaPaymentPublisher(producer);

        publisher.publishStatusReport("20000001", pacs002Multi()).block();

        assertEquals(2, producer.sends.size());
        assertEquals("E2E-1", PaymentStatusReport.parseFrom(producer.sends.get(0).value()).getPaymentId());
        assertEquals("E2E-2", PaymentStatusReport.parseFrom(producer.sends.get(1).value()).getPaymentId());
        assertRecordKey(producer.sends.get(0), "20000001");
        assertRecordKey(producer.sends.get(1), "20000001");
        assertAuthenticatedIspb(producer.sends.get(0), "20000001");
        assertAuthenticatedIspb(producer.sends.get(1), "20000001");
    }

    @Test
    void usesEachAuthenticatedIspbAsItsStatusRecordKey() throws Exception {
        FakeProducerClient producer = new FakeProducerClient();
        KafkaPaymentPublisher publisher = new KafkaPaymentPublisher(producer);

        publisher.publishStatusReport("20000001", pacs002("ACSP")).block();
        publisher.publishStatusReport("20000002", pacs002("ACSP")).block();

        assertRecordKey(producer.sends.get(0), "20000001");
        assertRecordKey(producer.sends.get(1), "20000002");
        assertEquals("E2E-1", PaymentStatusReport.parseFrom(producer.sends.get(0).value()).getPaymentId());
        assertEquals("E2E-1", PaymentStatusReport.parseFrom(producer.sends.get(1).value()).getPaymentId());
    }

    @Test
    void propagatesKafkaSendFailures() {
        FakeProducerClient producer = new FakeProducerClient();
        producer.failure = new IllegalStateException("send failed");
        KafkaPaymentPublisher publisher = new KafkaPaymentPublisher(producer);

        assertThrows(IllegalStateException.class,
                () -> publisher.publishPaymentRequest("10000001", pacs008()).block());
    }

    @Test
    void rejectsMalformedPacs008WithoutPublishing() {
        FakeProducerClient producer = new FakeProducerClient();
        KafkaPaymentPublisher publisher = new KafkaPaymentPublisher(producer);

        assertThrows(InvalidPacsPayloadException.class,
                () -> publisher.publishPaymentRequest("10000001", "not-json".getBytes()).block());

        assertEquals(0, producer.sends.size());
    }

    @Test
    void rejectsPacs008WithoutRequiredConversionFields() {
        FakeProducerClient producer = new FakeProducerClient();
        KafkaPaymentPublisher publisher = new KafkaPaymentPublisher(producer);

        assertThrows(InvalidPacsPayloadException.class,
                () -> publisher.publishPaymentRequest(
                        "10000001",
                        "{\"CdtTrfTxInf\":[{\"IntrBkSttlmAmt\":{\"value\":1,\"Ccy\":\"BRL\"}}]}".getBytes())
                        .block());

        assertEquals(0, producer.sends.size());
    }

    @Test
    void rejectsPacs008AmountThatCannotBeRepresentedInCents() {
        FakeProducerClient producer = new FakeProducerClient();
        KafkaPaymentPublisher publisher = new KafkaPaymentPublisher(producer);
        byte[] payload = new String(pacs008(), StandardCharsets.UTF_8)
                .replace("12.34", "12.345")
                .getBytes(StandardCharsets.UTF_8);

        assertThrows(InvalidPacsPayloadException.class,
                () -> publisher.publishPaymentRequest("10000001", payload).block());

        assertEquals(0, producer.sends.size());
    }

    @Test
    void rejectsUnsupportedPacs002StatusWithoutPublishing() {
        FakeProducerClient producer = new FakeProducerClient();
        KafkaPaymentPublisher publisher = new KafkaPaymentPublisher(producer);

        assertThrows(InvalidPacsPayloadException.class,
                () -> publisher.publishStatusReport("20000001", pacs002("PDNG")).block());

        assertEquals(0, producer.sends.size());
    }

    @Test
    void propagatesFailureAfterARecordFromTheSameEnvelopeWasConfirmed() {
        FakeProducerClient producer = new FakeProducerClient();
        producer.failOnSend = 2;
        KafkaPaymentPublisher publisher = new KafkaPaymentPublisher(producer);

        assertThrows(IllegalStateException.class,
                () -> publisher.publishPaymentRequest("10000001", pacs008Multi()).block());

        assertEquals(2, producer.sends.size());
    }

    @Test
    void warmsUpBothTopicsAndClosesTheProducer() {
        FakeProducerClient producer = new FakeProducerClient();
        KafkaPaymentPublisher publisher = new KafkaPaymentPublisher(producer);

        publisher.warmUp();
        publisher.close();

        assertEquals(List.of("spi-payment-requests", "spi-payment-status-reports"), producer.warmedTopics);
        assertEquals(1, producer.closeCalls);
    }

    private static void assertAuthenticatedIspb(ProducerRecord<byte[], byte[]> record, String expectedIspb) {
        List<Header> headers = new ArrayList<>();
        record.headers().headers(KafkaPaymentPublisher.AUTHENTICATED_ISPB_HEADER).forEach(headers::add);
        assertEquals(1, headers.size());
        assertEquals(expectedIspb, new String(headers.getFirst().value(), StandardCharsets.UTF_8));
    }

    private static void assertRecordKey(ProducerRecord<byte[], byte[]> record, String expectedIspb) {
        assertArrayEquals(expectedIspb.getBytes(StandardCharsets.UTF_8), record.key());
    }

    private static final class FakeProducerClient implements ProducerClient {
        final List<ProducerRecord<byte[], byte[]>> sends = new ArrayList<>();
        final List<String> warmedTopics = new ArrayList<>();
        RuntimeException failure;
        int failOnSend;
        int closeCalls;

        @Override
        public void send(ProducerRecord<byte[], byte[]> record, SendCallback callback) {
            sends.add(record);
            RuntimeException sendFailure = failOnSend == sends.size()
                    ? new IllegalStateException("send failed")
                    : failure;
            callback.complete(sendFailure);
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
