package br.kauan.paymentserviceprovider.adapter.output.listener;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class CentralTransferSystemRestClient {

    static final String SSL_BUNDLE_NAME = "central-transfer";

    private final URI baseUri;
    private final HttpClient httpClient;

    public CentralTransferSystemRestClient(
            SslBundles sslBundles,
            @Value("${external.central-transfer.url}") String centralTransferUrl
    ) {
        this.baseUri = requireHttps(centralTransferUrl);
        SSLContext sslContext = sslBundles.getBundle(SSL_BUNDLE_NAME).createSslContext();
        this.httpClient = createHttpClient(sslContext);
    }

    public void requestTransfer(byte[] transferRequest) {
        post("/transfer", transferRequest);
    }

    public void sendTransferStatus(byte[] statusReport) {
        post("/transfer/status", statusReport);
    }

    HttpClient httpClient() {
        return httpClient;
    }

    private void post(String path, byte[] body) {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Central transfer returned HTTP " + response.statusCode());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Central transfer request was interrupted", interrupted);
        } catch (IOException transportFailure) {
            throw new IllegalStateException("Central transfer request failed", transportFailure);
        }
    }

    private static HttpClient createHttpClient(SSLContext sslContext) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(5))
                .sslContext(sslContext)
                .build();
    }

    private static URI requireHttps(String centralTransferUrl) {
        URI uri;
        try {
            uri = URI.create(centralTransferUrl);
        } catch (IllegalArgumentException invalidUrl) {
            throw new IllegalStateException("Invalid central transfer URL: " + centralTransferUrl, invalidUrl);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException("Central transfer URL must use HTTPS: " + centralTransferUrl);
        }
        return uri;
    }
}
