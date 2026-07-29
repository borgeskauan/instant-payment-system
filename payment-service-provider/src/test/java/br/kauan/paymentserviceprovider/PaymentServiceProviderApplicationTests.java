package br.kauan.paymentserviceprovider;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@SpringBootTest(properties = {
        "bank.code=12345678",
        "notification.gateway.client-enabled=false",
        "notification.gateway.reconnect-delay=1m"
})
class PaymentServiceProviderApplicationTests {

    private static final TestTlsMaterial TLS = TestTlsMaterial.create();

    @DynamicPropertySource
    static void centralTransferTlsProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.ssl.bundle.pem.central-transfer.keystore.certificate",
                () -> TLS.clientCertificate().toString());
        registry.add(
                "spring.ssl.bundle.pem.central-transfer.keystore.private-key",
                () -> TLS.clientPrivateKey().toString());
        registry.add(
                "spring.ssl.bundle.pem.central-transfer.truststore.certificate",
                () -> TLS.caCertificate().toString());
    }

    @AfterAll
    static void cleanUpCertificates() throws IOException {
        TLS.close();
    }

    @Test
    void contextLoads() {
    }

    private record TestTlsMaterial(
            Path root,
            Path caCertificate,
            Path clientCertificate,
            Path clientPrivateKey
    ) {

        static TestTlsMaterial create() {
            try {
                Path root = Files.createTempDirectory("psp-central-transfer-mtls-test-");
                Path script = root.resolve("generate-local-mtls-certs.sh");
                Files.copy(certificateScript(), script);
                run(script, "init");
                run(script, "psp", "12345678");

                Path local = root.resolve("local");
                return new TestTlsMaterial(
                        root,
                        local.resolve("ca/ca.crt"),
                        local.resolve("psp-12345678/client.crt"),
                        local.resolve("psp-12345678/client.key"));
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        void close() throws IOException {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }

        private static Path certificateScript() {
            List<Path> candidates = List.of(
                    Path.of("..", "infra", "certs", "generate-local-mtls-certs.sh"),
                    Path.of("infra", "certs", "generate-local-mtls-certs.sh"));
            return candidates.stream()
                    .map(path -> path.toAbsolutePath().normalize())
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Certificate generation script was not found"));
        }

        private static void run(Path script, String... arguments) throws Exception {
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
    }
}
