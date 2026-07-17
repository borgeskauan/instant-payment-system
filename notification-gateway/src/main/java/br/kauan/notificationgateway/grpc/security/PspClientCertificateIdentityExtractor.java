package br.kauan.notificationgateway.grpc.security;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

public final class PspClientCertificateIdentityExtractor {

    private static final int URI_SAN_TYPE = 6;
    private static final String ISPB_SAN_PREFIX = "urn:pix:ispb:";
    private static final Pattern ISPB_PATTERN = Pattern.compile("\\d{8}");

    private PspClientCertificateIdentityExtractor() {
    }

    public static String extractIspb(SSLSession sslSession) {
        if (sslSession == null) {
            throw unauthenticated("client certificate is required");
        }

        X509Certificate certificate = peerCertificate(sslSession);
        try {
            Collection<List<?>> subjectAlternativeNames = certificate.getSubjectAlternativeNames();
            if (subjectAlternativeNames != null) {
                for (List<?> subjectAlternativeName : subjectAlternativeNames) {
                    String ispb = ispbFromSubjectAlternativeName(subjectAlternativeName);
                    if (ispb != null) {
                        return ispb;
                    }
                }
            }
        } catch (CertificateParsingException e) {
            throw unauthenticated("client certificate subjectAltName could not be parsed");
        }

        throw unauthenticated("client certificate must contain SAN URI urn:pix:ispb:<ISPB>");
    }

    private static X509Certificate peerCertificate(SSLSession sslSession) {
        try {
            Certificate[] certificates = sslSession.getPeerCertificates();
            if (certificates == null
                    || certificates.length == 0
                    || !(certificates[0] instanceof X509Certificate certificate)) {
                throw unauthenticated("client certificate must be an X509 certificate");
            }
            return certificate;
        } catch (SSLPeerUnverifiedException e) {
            throw unauthenticated("client certificate is required");
        }
    }

    private static String ispbFromSubjectAlternativeName(List<?> subjectAlternativeName) {
        if (subjectAlternativeName.size() < 2 || !(subjectAlternativeName.get(0) instanceof Integer type)) {
            return null;
        }
        if (type != URI_SAN_TYPE || !(subjectAlternativeName.get(1) instanceof String uri)) {
            return null;
        }
        if (!uri.startsWith(ISPB_SAN_PREFIX)) {
            return null;
        }

        String ispb = uri.substring(ISPB_SAN_PREFIX.length());
        if (!ISPB_PATTERN.matcher(ispb).matches()) {
            return null;
        }
        return ispb;
    }

    private static StatusRuntimeException unauthenticated(String description) {
        return Status.UNAUTHENTICATED
                .withDescription(description)
                .asRuntimeException();
    }
}
