package br.kauan.paymentserviceprovider.adapter.output.listener;

import feign.Client;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.net.URI;

@FeignClient(
        name = "central-transfer",
        url = "${external.central-transfer.url}",
        configuration = CentralTransferFeignConfiguration.class
)
public interface CentralTransferSystemRestClient {

    @PostMapping(value = "/{ispb}/transfer", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    void requestTransfer(@PathVariable String ispb, @RequestBody byte[] transferRequest);

    @PostMapping(value = "/{ispb}/transfer/status", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    void sendTransferStatus(@PathVariable String ispb, @RequestBody byte[] statusReport);

    @GetMapping("/{ispb}/messages")
    SpiNotification getMessages(@PathVariable String ispb);
}

class CentralTransferFeignConfiguration {

    static final String SSL_BUNDLE_NAME = "central-transfer";

    @Bean
    Client centralTransferFeignClient(
            SslBundles sslBundles,
            @Value("${external.central-transfer.url}") String centralTransferUrl
    ) {
        requireHttps(centralTransferUrl);
        SSLContext sslContext = sslBundles.getBundle(SSL_BUNDLE_NAME).createSslContext();
        return new Client.Default(
                sslContext.getSocketFactory(),
                HttpsURLConnection.getDefaultHostnameVerifier());
    }

    private void requireHttps(String centralTransferUrl) {
        URI uri;
        try {
            uri = URI.create(centralTransferUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid central transfer URL: " + centralTransferUrl, e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException("Central transfer URL must use HTTPS: " + centralTransferUrl);
        }
    }
}
