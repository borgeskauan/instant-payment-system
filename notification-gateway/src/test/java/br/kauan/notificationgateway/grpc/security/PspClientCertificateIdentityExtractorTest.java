package br.kauan.notificationgateway.grpc.security;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PspClientCertificateIdentityExtractorTest {

    @Test
    void extractsIspbFromSanUri() throws Exception {
        X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(
                List.of(2, "psp.local"),
                List.of(6, "urn:pix:ispb:20000001")
        ));
        SSLSession sslSession = mock(SSLSession.class);
        when(sslSession.getPeerCertificates()).thenReturn(new Certificate[]{certificate});

        String ispb = PspClientCertificateIdentityExtractor.extractIspb(sslSession);

        assertThat(ispb).isEqualTo("20000001");
    }

    @Test
    void rejectsSessionWithoutVerifiedPeerCertificate() throws Exception {
        SSLSession sslSession = mock(SSLSession.class);
        when(sslSession.getPeerCertificates()).thenThrow(new SSLPeerUnverifiedException("missing peer"));

        assertThatThrownBy(() -> PspClientCertificateIdentityExtractor.extractIspb(sslSession))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(throwable -> ((StatusRuntimeException) throwable).getStatus().getCode())
                .isEqualTo(Status.UNAUTHENTICATED.getCode());
    }

    @Test
    void rejectsCertificateWithoutValidIspbSanUri() throws Exception {
        X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(
                List.of(6, "urn:pix:ispb:not-valid")
        ));
        SSLSession sslSession = mock(SSLSession.class);
        when(sslSession.getPeerCertificates()).thenReturn(new Certificate[]{certificate});

        assertThatThrownBy(() -> PspClientCertificateIdentityExtractor.extractIspb(sslSession))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(throwable -> ((StatusRuntimeException) throwable).getStatus().getCode())
                .isEqualTo(Status.UNAUTHENTICATED.getCode());
    }
}
