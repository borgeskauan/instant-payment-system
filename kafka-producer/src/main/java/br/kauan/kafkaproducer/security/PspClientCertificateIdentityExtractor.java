package br.kauan.kafkaproducer.security;

import io.netty.handler.ssl.SslHandler;
import reactor.netty.http.server.HttpServerRequest;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

public final class PspClientCertificateIdentityExtractor {

    private static final int URI_SAN_TYPE = 6;
    private static final String ISPB_SAN_PREFIX = "urn:pix:ispb:";
    private static final Pattern ISPB_PATTERN = Pattern.compile("\\d{8}");

    private PspClientCertificateIdentityExtractor() {
    }

    public static String extractIspb(HttpServerRequest request) {
        AtomicReference<SSLSession> sslSession = new AtomicReference<>();
        request.withConnection(connection -> {
            SslHandler sslHandler = connection.channel().pipeline().get(SslHandler.class);
            if (sslHandler != null) {
                sslSession.set(sslHandler.engine().getSession());
            }
        });
        return extractIspb(sslSession.get());
    }

    static String extractIspb(SSLSession sslSession) {
        if (sslSession == null) {
            throw unauthenticated("client certificate is required");
        }

        try {
            Certificate[] certificates = sslSession.getPeerCertificates();
            if (certificates == null
                    || certificates.length == 0
                    || !(certificates[0] instanceof X509Certificate certificate)) {
                throw unauthenticated("client certificate must be an X509 certificate");
            }
            return extractIspb(certificate);
        } catch (SSLPeerUnverifiedException e) {
            throw unauthenticated("client certificate is required", e);
        }
    }

    static String extractIspb(X509Certificate certificate) {
        try {
            return extractIspb(certificate.getSubjectAlternativeNames());
        } catch (CertificateParsingException e) {
            throw unauthenticated("client certificate subjectAltName could not be parsed", e);
        }
    }

    static String extractIspb(Collection<List<?>> subjectAlternativeNames) {
        String authenticatedIspb = null;
        if (subjectAlternativeNames != null) {
            for (List<?> subjectAlternativeName : subjectAlternativeNames) {
                String candidate = ispbCandidate(subjectAlternativeName);
                if (candidate == null) {
                    continue;
                }
                if (!ISPB_PATTERN.matcher(candidate).matches()) {
                    throw unauthenticated("client certificate contains a malformed PSP identity");
                }
                if (authenticatedIspb != null) {
                    throw unauthenticated("client certificate contains multiple PSP identities");
                }
                authenticatedIspb = candidate;
            }
        }

        if (authenticatedIspb == null) {
            throw unauthenticated("client certificate must contain SAN URI urn:pix:ispb:<ISPB>");
        }
        return authenticatedIspb;
    }

    private static String ispbCandidate(List<?> subjectAlternativeName) {
        if (subjectAlternativeName.size() < 2
                || !(subjectAlternativeName.get(0) instanceof Integer type)
                || type != URI_SAN_TYPE
                || !(subjectAlternativeName.get(1) instanceof String uri)
                || !uri.startsWith(ISPB_SAN_PREFIX)) {
            return null;
        }
        return uri.substring(ISPB_SAN_PREFIX.length());
    }

    private static PspAuthenticationException unauthenticated(String message) {
        return new PspAuthenticationException(message);
    }

    private static PspAuthenticationException unauthenticated(String message, Throwable cause) {
        return new PspAuthenticationException(message, cause);
    }
}
