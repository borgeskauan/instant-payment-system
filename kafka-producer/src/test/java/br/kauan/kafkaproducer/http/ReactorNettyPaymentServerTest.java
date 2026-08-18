package br.kauan.kafkaproducer.http;

import br.kauan.kafkaproducer.kafka.PaymentPublisher;
import br.kauan.kafkaproducer.security.PspAuthenticationException;
import br.kauan.kafkaproducer.security.PspAuthorizationException;

import io.netty.buffer.Unpooled;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.HttpProtocol;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLHandshakeException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReactorNettyPaymentServerTest {

    private Path temporaryDirectory;
    private TlsMaterial trustedMaterial;
    private TlsMaterial untrustedMaterial;
    private SslContext serverSslContext;
    private SslContext trustedClientSslContext;
    private SslContext clientWithoutCertificateSslContext;
    private SslContext untrustedClientSslContext;
    private SslContext trustedHttp11ClientSslContext;

    @BeforeAll
    void setUpTls() throws Exception {
        temporaryDirectory = Files.createTempDirectory("kafka-producer-mtls-test-");
        trustedMaterial = generateTlsMaterial(temporaryDirectory.resolve("trusted"), "12345678");
        untrustedMaterial = generateTlsMaterial(temporaryDirectory.resolve("untrusted"), "87654321");

        serverSslContext = ServerSslContextFactory.create(
                trustedMaterial.serverCertificate(),
                trustedMaterial.serverPrivateKey(),
                trustedMaterial.caCertificate());
        trustedClientSslContext = http2ClientSslContext(trustedMaterial, trustedMaterial);
        clientWithoutCertificateSslContext = http2ClientWithoutCertificateSslContext(trustedMaterial);
        untrustedClientSslContext = http2ClientSslContext(trustedMaterial, untrustedMaterial);
        trustedHttp11ClientSslContext = http11ClientSslContext(trustedMaterial, trustedMaterial);
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
            int status = post(server, trustedClientSslContext, "/transfer", "pacs008".getBytes());

            assertEquals(200, status);
            assertEquals("12345678", publisher.paymentRequests.getFirst().authenticatedIspb());
            assertArrayEquals("pacs008".getBytes(), publisher.paymentRequests.getFirst().payload());
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
                    "/transfer/status",
                    "pacs002".getBytes());

            assertEquals(200, status);
            assertEquals("12345678", publisher.statusReports.getFirst().authenticatedIspb());
            assertArrayEquals("pacs002".getBytes(), publisher.statusReports.getFirst().payload());
            assertEquals(0, publisher.paymentRequests.size());
        }
    }

    @Test
    void ignoresSpoofedAuthenticatedIspbHttpHeader() throws Exception {
        FakePaymentPublisher publisher = new FakePaymentPublisher();

        try (RunningServer server = start(publisher)) {
            int status = post(
                    server,
                    trustedClientSslContext,
                    "/transfer",
                    "pacs008".getBytes(),
                    "99999999");

            assertEquals(200, status);
            assertEquals("12345678", publisher.paymentRequests.getFirst().authenticatedIspb());
        }
    }

    @Test
    void oldIspbRoutesAreNotAvailable() throws Exception {
        try (RunningServer server = start(new FakePaymentPublisher())) {
            int status = post(
                    server,
                    trustedClientSslContext,
                    "/12345678/transfer",
                    "pacs008".getBytes());

            assertEquals(404, status);
        }
    }

    @Test
    void returnsServerErrorWhenPublisherFails() throws Exception {
        FakePaymentPublisher publisher = new FakePaymentPublisher();
        publisher.failure = new IllegalStateException("send failed");

        try (RunningServer server = start(publisher)) {
            int status = post(server, trustedClientSslContext, "/transfer", "pacs008".getBytes());

            assertEquals(500, status);
        }
    }

    @Test
    void returnsForbiddenWhenPublisherRejectsPspAuthorization() throws Exception {
        FakePaymentPublisher publisher = new FakePaymentPublisher();
        publisher.failure = new PspAuthorizationException("sender does not match certificate");

        try (RunningServer server = start(publisher)) {
            int status = post(server, trustedClientSslContext, "/transfer", "pacs008".getBytes());

            assertEquals(403, status);
        }
    }

    @Test
    void returnsUnauthorizedWhenCertificateIdentityCannotBeExtracted() throws Exception {
        DisposableServer disposableServer = new ReactorNettyPaymentServer(
                0,
                new FakePaymentPublisher(),
                serverSslContext,
                ignored -> {
                    throw new PspAuthenticationException("missing PSP identity");
                }).start();

        try (RunningServer server = new RunningServer(disposableServer)) {
            int status = post(server, trustedClientSslContext, "/transfer", "pacs008".getBytes());

            assertEquals(401, status);
        }
    }

    @Test
    void healthAuthenticatesPspWithoutPublishing() {
        FakePaymentPublisher publisher = new FakePaymentPublisher();

        try (RunningServer server = start(publisher)) {
            int status = get(server, trustedClientSslContext, "/health");

            assertEquals(200, status);
            assertEquals(0, publisher.paymentRequests.size());
            assertEquals(0, publisher.statusReports.size());
        }
    }

    @Test
    void serverSslContextAdvertisesOnlyH2() {
        assertEquals(
                List.of(ApplicationProtocolNames.HTTP_2),
                serverSslContext.applicationProtocolNegotiator().protocols());
    }

    @Test
    void failsAlpnNegotiationForHttp11OnlyTrustedClient() {
        try (RunningServer server = start(new FakePaymentPublisher())) {
            RuntimeException error = assertThrows(RuntimeException.class, () -> HttpClient.create()
                    .protocol(HttpProtocol.HTTP11)
                    .secure(spec -> spec.sslContext(trustedHttp11ClientSslContext))
                    .responseTimeout(Duration.ofSeconds(5))
                    .get()
                    .uri("https://localhost:" + server.port() + "/health")
                    .response()
                    .block(Duration.ofSeconds(5)));
            assertTrue(hasCause(error, SSLHandshakeException.class),
                    () -> "HTTP/1.1-only client failed after TLS negotiation instead of during ALPN: " + error);
        }
    }

    @Test
    void healthReturnsUnauthorizedWhenCertificateIdentityCannotBeExtracted() {
        DisposableServer disposableServer = new ReactorNettyPaymentServer(
                0,
                new FakePaymentPublisher(),
                serverSslContext,
                ignored -> {
                    throw new PspAuthenticationException("missing PSP identity");
                }).start();

        try (RunningServer server = new RunningServer(disposableServer)) {
            assertEquals(401, get(server, trustedClientSslContext, "/health"));
        }
    }

    @Test
    void transferResponseWaitsForPublisherCompletion() throws Exception {
        FakePaymentPublisher publisher = new FakePaymentPublisher();
        publisher.paymentCompletion = Sinks.empty();

        try (RunningServer server = start(publisher)) {
            CompletableFuture<Integer> response = postResponse(
                    server, trustedClientSslContext, "/transfer", "pacs008".getBytes(), null).toFuture();

            assertTrue(publisher.paymentInvoked.await(5, TimeUnit.SECONDS));
            assertFalse(response.isDone());
            publisher.paymentCompletion.tryEmitEmpty();
            assertEquals(200, response.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void transferStatusResponseWaitsForPublisherCompletion() throws Exception {
        FakePaymentPublisher publisher = new FakePaymentPublisher();
        publisher.statusCompletion = Sinks.empty();

        try (RunningServer server = start(publisher)) {
            CompletableFuture<Integer> response = postResponse(
                    server, trustedClientSslContext, "/transfer/status", "pacs002".getBytes(), null).toFuture();

            assertTrue(publisher.statusInvoked.await(5, TimeUnit.SECONDS));
            assertFalse(response.isDone());
            publisher.statusCompletion.tryEmitEmpty();
            assertEquals(200, response.get(5, TimeUnit.SECONDS));
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
                            "/transfer",
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
                            "/transfer",
                            "pacs008".getBytes()));
        }
    }

    @Test
    void rejectsPlaintextConnection() {
        try (RunningServer server = start(new FakePaymentPublisher())) {
            assertThrows(RuntimeException.class, () -> HttpClient.create()
                    .responseTimeout(Duration.ofSeconds(5))
                    .post()
                    .uri("http://127.0.0.1:" + server.port() + "/transfer")
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
        return post(server, clientSslContext, path, payload, null);
    }

    private int post(
            RunningServer server,
            SslContext clientSslContext,
            String path,
            byte[] payload,
            String spoofedAuthenticatedIspb
    ) {
        return postResponse(server, clientSslContext, path, payload, spoofedAuthenticatedIspb)
                .block(Duration.ofSeconds(5));
    }

    private Mono<Integer> postResponse(
            RunningServer server,
            SslContext clientSslContext,
            String path,
            byte[] payload,
            String spoofedAuthenticatedIspb
    ) {
        return http2Client(clientSslContext)
                .headers(headers -> {
                    headers.set("Content-Type", "application/octet-stream");
                    if (spoofedAuthenticatedIspb != null) {
                        headers.set("authenticated-ispb", spoofedAuthenticatedIspb);
                    }
                })
                .post()
                .uri("https://localhost:" + server.port() + path)
                .send(Mono.just(Unpooled.wrappedBuffer(payload)))
                .responseSingle((response, body) -> body.thenReturn(response.status().code()));
    }

    private int get(RunningServer server, SslContext clientSslContext, String path) {
        return http2Client(clientSslContext)
                .get()
                .uri("https://localhost:" + server.port() + path)
                .responseSingle((response, body) -> body.thenReturn(response.status().code()))
                .block(Duration.ofSeconds(5));
    }

    private SslContext http2ClientSslContext(TlsMaterial trustedServer, TlsMaterial client) throws Exception {
        return Http2SslContextSpec.forClient()
                .configure(builder -> builder
                        .trustManager(trustedServer.caCertificate().toFile())
                        .keyManager(client.clientCertificate().toFile(), client.clientPrivateKey().toFile()))
                .sslContext();
    }

    private SslContext http2ClientWithoutCertificateSslContext(TlsMaterial trustedServer) throws Exception {
        return Http2SslContextSpec.forClient()
                .configure(builder -> builder.trustManager(trustedServer.caCertificate().toFile()))
                .sslContext();
    }

    private SslContext http11ClientSslContext(TlsMaterial trustedServer, TlsMaterial client) throws Exception {
        return SslContextBuilder.forClient()
                .trustManager(trustedServer.caCertificate().toFile())
                .keyManager(client.clientCertificate().toFile(), client.clientPrivateKey().toFile())
                .applicationProtocolConfig(new ApplicationProtocolConfig(
                        ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.FATAL_ALERT,
                        ApplicationProtocolNames.HTTP_1_1))
                .build();
    }

    private boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    private HttpClient http2Client(SslContext clientSslContext) {
        return HttpClient.create()
                .protocol(HttpProtocol.H2)
                .secure(spec -> spec.sslContext(clientSslContext))
                .responseTimeout(Duration.ofSeconds(5));
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
        final List<AuthenticatedPayload> paymentRequests = new ArrayList<>();
        final List<AuthenticatedPayload> statusReports = new ArrayList<>();
        final CountDownLatch paymentInvoked = new CountDownLatch(1);
        final CountDownLatch statusInvoked = new CountDownLatch(1);
        RuntimeException failure;
        Sinks.Empty<Void> paymentCompletion;
        Sinks.Empty<Void> statusCompletion;

        @Override
        public Mono<Void> publishPaymentRequest(String authenticatedIspb, byte[] payload) {
            paymentRequests.add(new AuthenticatedPayload(authenticatedIspb, payload));
            paymentInvoked.countDown();
            if (paymentCompletion != null) {
                return paymentCompletion.asMono();
            }
            return failure == null ? Mono.empty() : Mono.error(failure);
        }

        @Override
        public Mono<Void> publishStatusReport(String authenticatedIspb, byte[] payload) {
            statusReports.add(new AuthenticatedPayload(authenticatedIspb, payload));
            statusInvoked.countDown();
            if (statusCompletion != null) {
                return statusCompletion.asMono();
            }
            return failure == null ? Mono.empty() : Mono.error(failure);
        }

        @Override
        public void warmUp() {
        }

        @Override
        public void close() {
        }
    }

    private record AuthenticatedPayload(String authenticatedIspb, byte[] payload) {
    }
}
