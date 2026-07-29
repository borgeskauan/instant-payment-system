package br.kauan.paymentserviceprovider.adapter.output.listener;

import feign.Client;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;

import javax.net.ssl.SSLContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CentralTransferFeignConfigurationTest {

    @Test
    void createsFeignClientFromCentralTransferSslBundle() throws Exception {
        SslBundles sslBundles = mock(SslBundles.class);
        SslBundle sslBundle = mock(SslBundle.class);
        when(sslBundles.getBundle(CentralTransferFeignConfiguration.SSL_BUNDLE_NAME))
                .thenReturn(sslBundle);
        when(sslBundle.createSslContext()).thenReturn(SSLContext.getDefault());

        Client client = new CentralTransferFeignConfiguration()
                .centralTransferFeignClient(sslBundles, "https://kafka-producer:8001");

        assertThat(client).isInstanceOf(Client.Default.class);
        verify(sslBundles).getBundle("central-transfer");
    }

    @Test
    void rejectsPlaintextCentralTransferUrl() {
        assertThatThrownBy(() -> new CentralTransferFeignConfiguration()
                .centralTransferFeignClient(mock(SslBundles.class), "http://kafka-producer:8001"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Central transfer URL must use HTTPS: http://kafka-producer:8001");
    }
}
