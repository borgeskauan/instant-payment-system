package br.kauan.notificationgateway.grpc.security;

import io.grpc.Attributes;
import io.grpc.Context;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.net.ssl.SSLSession;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class PspClientCertificateInterceptorTest {

    @Test
    void closesCallWithoutSslSessionAsUnauthenticated() {
        PspClientCertificateInterceptor interceptor = new PspClientCertificateInterceptor();
        ServerCall<Object, Object> call = serverCall(Attributes.EMPTY);
        ServerCallHandler<Object, Object> next = mock(ServerCallHandler.class);

        interceptor.interceptCall(call, new Metadata(), next);

        ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
        verify(call).close(statusCaptor.capture(), org.mockito.ArgumentMatchers.any(Metadata.class));
        assertThat(statusCaptor.getValue().getCode()).isEqualTo(Status.UNAUTHENTICATED.getCode());
        verify(next, never()).startCall(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void closesCallWithInvalidCertificateIdentityAsUnauthenticated() throws Exception {
        PspClientCertificateInterceptor interceptor = new PspClientCertificateInterceptor();
        SSLSession sslSession = sslSessionWithSanUri("urn:pix:ispb:not-valid");
        ServerCall<Object, Object> call = serverCall(Attributes.newBuilder()
                .set(Grpc.TRANSPORT_ATTR_SSL_SESSION, sslSession)
                .build());
        ServerCallHandler<Object, Object> next = mock(ServerCallHandler.class);

        interceptor.interceptCall(call, new Metadata(), next);

        ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
        verify(call).close(statusCaptor.capture(), org.mockito.ArgumentMatchers.any(Metadata.class));
        assertThat(statusCaptor.getValue().getCode()).isEqualTo(Status.UNAUTHENTICATED.getCode());
        verify(next, never()).startCall(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void validCertificateIdentityIsStoredInGrpcContext() throws Exception {
        PspClientCertificateInterceptor interceptor = new PspClientCertificateInterceptor();
        SSLSession sslSession = sslSessionWithSanUri("urn:pix:ispb:20000001");
        ServerCall<Object, Object> call = serverCall(Attributes.newBuilder()
                .set(Grpc.TRANSPORT_ATTR_SSL_SESSION, sslSession)
                .build());
        CapturingHandler next = new CapturingHandler();

        interceptor.interceptCall(call, new Metadata(), next);

        assertThat(next.authenticatedIspb).isEqualTo("20000001");
    }

    @SuppressWarnings("unchecked")
    private static ServerCall<Object, Object> serverCall(Attributes attributes) {
        ServerCall<Object, Object> call = mock(ServerCall.class);
        when(call.getAttributes()).thenReturn(attributes);
        return call;
    }

    private static SSLSession sslSessionWithSanUri(String sanUri) throws Exception {
        X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6, sanUri)));
        SSLSession sslSession = mock(SSLSession.class);
        when(sslSession.getPeerCertificates()).thenReturn(new Certificate[]{certificate});
        return sslSession;
    }

    private static final class CapturingHandler implements ServerCallHandler<Object, Object> {

        private String authenticatedIspb;

        @Override
        public ServerCall.Listener<Object> startCall(ServerCall<Object, Object> call, Metadata headers) {
            authenticatedIspb = AuthenticatedPspContext.AUTHENTICATED_ISPB.get(Context.current());
            return new ServerCall.Listener<>() {
            };
        }
    }
}
