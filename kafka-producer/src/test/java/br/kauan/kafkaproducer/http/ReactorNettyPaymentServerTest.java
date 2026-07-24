package br.kauan.kafkaproducer.http;

import br.kauan.kafkaproducer.kafka.PaymentPublisher;

import io.netty.buffer.Unpooled;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReactorNettyPaymentServerTest {

    private Path temporaryDirectory;
    private TlsMaterial trustedMaterial;
    private TlsMaterial untrustedMaterial;
    private SslContext serverSslContext;
    private SslContext trustedClientSslContext;
    private SslContext clientWithoutCertificateSslContext;
    private SslContext untrustedClientSslContext;

    @BeforeAll
    void setUpTls() throws Exception {
        temporaryDirectory = Files.createTempDirectory("kafka-producer-mtls-test-");
        trustedMaterial = generateTlsMaterial(temporaryDirectory.resolve("trusted"), "12345678");
        untrustedMaterial = generateTlsMaterial(temporaryDirectory.resolve("untrusted"), "87654321");

        serverSslContext = ServerSslContextFactory.create(
                trustedMaterial.serverCertificate(),
                trustedMaterial.serverPrivateKey(),
                trustedMaterial.caCertificate());
        trustedClientSslContext = clientSslContext(trustedMaterial, trustedMaterial);
        clientWithoutCertificateSslContext = SslContextBuilder.forClient()
                .trustManager(trustedMaterial.caCertificate().toFile())
                .build();
        untrustedClientSslContext = clientSslContext(trustedMaterial, untrustedMaterial);
    }

    @AfterAll
    void cleanUpTls() throws IOException {
        if (temporaryDirectory == null) {
            return;
        }
        try (var paths = Files.walk(temporaryDirectory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void transferRoutePublishesPaymentRequestPayload() throws Exception {
        FakePaymentPublisher publisher = new FakePaymentPublisher();

        try (RunningServer server = start(publisher)) {
            int status = post(server, trustedClientSslContext, "/12345678/transfer", "pacs008".getBytes());

            assertEquals(200, status);
            assertArrayEquals("pacs008".getBytes(), publisher.paymentRequests.getFirst());
            assertEquals(0, publisher.statusReports.size());
        }
    }

    @Test
    void transferStatusRoutePublishesStatusReportPayload() throws Exception {
        FakePaymentPublisher publisher = new FakePaymentPublisher();

        try (RunningServer server = start(publisher)) {
            int status = post(
                    server,
                    trustedClientSslContext,
                    "/12345678/transfer/status",
                    "pacs002".getBytes());

            assertEquals(200, status);
            assertArrayEquals("pacs002".getBytes(), publisher.statusReports.getFirst());
            assertEquals(0, publisher.paymentRequests.size());
        }
    }

    @Test
    void returnsServerErrorWhenPublisherFails() throws Exception {
        FakePaymentPublisher publisher = new FakePaymentPublisher();
        publisher.failure = new IllegalStateException("send failed");

        try (RunningServer server = start(publisher)) {
            int status = post(server, trustedClientSslContext, "/12345678/transfer", "pacs008".getBytes());

            assertEquals(500, status);
        }
    }

    @Test
    void rejectsClientWithoutCertificate() {
        try (RunningServer server = start(new FakePaymentPublisher())) {
            assertThrows(
                    RuntimeException.class,
                    () -> post(
                            server,
                            clientWithoutCertificateSslContext,
                            "/12345678/transfer",
                            "pacs008".getBytes()));
        }
    }

    @Test
    void rejectsClientCertificateSignedByAnotherCa() {
        try (RunningServer server = start(new FakePaymentPublisher())) {
            assertThrows(
                    RuntimeException.class,
                    () -> post(
                            server,
                            untrustedClientSslContext,
                            "/12345678/transfer",
                            "pacs008".getBytes()));
        }
    }

    @Test
    void rejectsPlaintextConnection() {
        try (RunningServer server = start(new FakePaymentPublisher())) {
            assertThrows(RuntimeException.class, () -> HttpClient.create()
                    .responseTimeout(Duration.ofSeconds(5))
                    .post()
                    .uri("http://127.0.0.1:" + server.port() + "/12345678/transfer")
                    .send(Mono.just(Unpooled.wrappedBuffer("pacs008".getBytes())))
                    .response()
                    .block(Duration.ofSeconds(5)));
        }
    }

    private RunningServer start(PaymentPublisher publisher) {
        DisposableServer server = new ReactorNettyPaymentServer(0, publisher, serverSslContext).start();
        return new RunningServer(server);
    }

    private int post(
            RunningServer server,
            SslContext clientSslContext,
            String path,
            byte[] payload
    ) {
        return HttpClient.create()
                .secure(sslProvider -> sslProvider.sslContext(clientSslContext))
                .responseTimeout(Duration.ofSeconds(5))
                .headers(headers -> headers.set("Content-Type", "application/octet-stream"))
                .post()
                .uri("https://localhost:" + server.port() + path)
                .send(Mono.just(Unpooled.wrappedBuffer(payload)))
                .responseSingle((response, body) -> body.thenReturn(response.status().code()))
                .block(Duration.ofSeconds(5));
    }

    private SslContext clientSslContext(TlsMaterial trustedServer, TlsMaterial client) throws Exception {
        return SslContextBuilder.forClient()
                .trustManager(trustedServer.caCertificate().toFile())
                .keyManager(client.clientCertificate().toFile(), client.clientPrivateKey().toFile())
                .build();
    }

    private TlsMaterial generateTlsMaterial(Path root, String ispb) throws Exception {
        Files.createDirectories(root);
        Path sourceScript = certificateScript();
        Path script = root.resolve("generate-local-mtls-certs.sh");
        Files.copy(sourceScript, script);

        run(script, "init");
        run(script, "psp", ispb);

        Path local = root.resolve("local");
        return new TlsMaterial(
                local.resolve("ca/ca.crt"),
                local.resolve("kafka-producer/server.crt"),
                local.resolve("kafka-producer/server.key"),
                local.resolve("psp-" + ispb + "/client.crt"),
                local.resolve("psp-" + ispb + "/client.key"));
    }

    private Path certificateScript() {
        List<Path> candidates = List.of(
                Path.of("..", "infra", "certs", "generate-local-mtls-certs.sh"),
                Path.of("infra", "certs", "generate-local-mtls-certs.sh"));
        return candidates.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Certificate generation script was not found"));
    }

    private void run(Path script, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("bash");
        command.add(script.toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Certificate command failed: " + output);
        }
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

    private record TlsMaterial(
            Path caCertificate,
            Path serverCertificate,
            Path serverPrivateKey,
            Path clientCertificate,
            Path clientPrivateKey
    ) {
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
