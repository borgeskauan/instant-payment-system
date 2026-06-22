package br.kauan.kafkaproducer.http;

import br.kauan.kafkaproducer.kafka.PaymentPublisher;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReactorNettyPaymentServerTest {

    @Test
    void transferRoutePublishesPaymentRequestPayload() throws Exception {
        FakePaymentPublisher publisher = new FakePaymentPublisher();

        try (RunningServer server = start(publisher)) {
            HttpResponse<Void> response = post(server, "/12345678/transfer", "pacs008".getBytes());

            assertEquals(200, response.statusCode());
            assertArrayEquals("pacs008".getBytes(), publisher.paymentRequests.getFirst());
            assertEquals(0, publisher.statusReports.size());
        }
    }

    @Test
    void transferStatusRoutePublishesStatusReportPayload() throws Exception {
        FakePaymentPublisher publisher = new FakePaymentPublisher();

        try (RunningServer server = start(publisher)) {
            HttpResponse<Void> response = post(server, "/12345678/transfer/status", "pacs002".getBytes());

            assertEquals(200, response.statusCode());
            assertArrayEquals("pacs002".getBytes(), publisher.statusReports.getFirst());
            assertEquals(0, publisher.paymentRequests.size());
        }
    }

    @Test
    void returnsServerErrorWhenPublisherFails() throws Exception {
        FakePaymentPublisher publisher = new FakePaymentPublisher();
        publisher.failure = new IllegalStateException("send failed");

        try (RunningServer server = start(publisher)) {
            HttpResponse<Void> response = post(server, "/12345678/transfer", "pacs008".getBytes());

            assertEquals(500, response.statusCode());
        }
    }

    private RunningServer start(PaymentPublisher publisher) {
        DisposableServer server = new ReactorNettyPaymentServer(0, publisher).start();
        return new RunningServer(server);
    }

    private HttpResponse<Void> post(RunningServer server, String path, byte[] payload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
    }

    private record RunningServer(DisposableServer server) implements AutoCloseable {
        int port() {
            return server.port();
        }

        @Override
        public void close() {
            server.disposeNow();
        }
    }

    private static final class FakePaymentPublisher implements PaymentPublisher {
        final List<byte[]> paymentRequests = new ArrayList<>();
        final List<byte[]> statusReports = new ArrayList<>();
        RuntimeException failure;

        @Override
        public Mono<Void> publishPaymentRequest(byte[] payload) {
            paymentRequests.add(payload);
            return failure == null ? Mono.empty() : Mono.error(failure);
        }

        @Override
        public Mono<Void> publishStatusReport(byte[] payload) {
            statusReports.add(payload);
            return failure == null ? Mono.empty() : Mono.error(failure);
        }

        @Override
        public void warmUp() {
        }

        @Override
        public void close() {
        }
    }
}
