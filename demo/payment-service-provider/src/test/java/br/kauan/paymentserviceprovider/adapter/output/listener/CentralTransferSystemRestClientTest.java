package br.kauan.paymentserviceprovider.adapter.output.listener;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;

import javax.net.ssl.SSLContext;
import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CentralTransferSystemRestClientTest {

    @Test
    void usesHttp2AndTheConfiguredTlsBundle() throws Exception {
        SslBundles sslBundles = mock(SslBundles.class);
        SslBundle sslBundle = mock(SslBundle.class);
        when(sslBundles.getBundle(CentralTransferSystemRestClient.SSL_BUNDLE_NAME)).thenReturn(sslBundle);
        when(sslBundle.createSslContext()).thenReturn(SSLContext.getDefault());

        CentralTransferSystemRestClient client = new CentralTransferSystemRestClient(
                sslBundles,
                "https://kafka-producer:8001"
        );

        assertThat(client.httpClient().version()).isEqualTo(HttpClient.Version.HTTP_2);
        verify(sslBundles).getBundle("central-transfer");
    }

    @Test
    void rejectsPlaintextCentralTransferUrl() {
        assertThatThrownBy(() -> new CentralTransferSystemRestClient(
                mock(SslBundles.class),
                "http://kafka-producer:8001"
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Central transfer URL must use HTTPS: http://kafka-producer:8001");
    }
}
