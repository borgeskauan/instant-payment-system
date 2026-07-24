package br.kauan.kafkaproducer.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PspClientCertificateIdentityExtractorTest {

    @Test
    void extractsTheSinglePspIdentityFromSubjectAlternativeNames() {
        String ispb = PspClientCertificateIdentityExtractor.extractIspb(List.of(
                san(2, "psp.example.test"),
                san(6, "urn:pix:ispb:12345678")));

        assertEquals("12345678", ispb);
    }

    @Test
    void rejectsCertificateWithoutPspIdentity() {
        assertThrows(PspAuthenticationException.class,
                () -> PspClientCertificateIdentityExtractor.extractIspb(List.of(
                        san(2, "psp.example.test"))));
    }

    @Test
    void rejectsMalformedPspIdentity() {
        assertThrows(PspAuthenticationException.class,
                () -> PspClientCertificateIdentityExtractor.extractIspb(List.of(
                        san(6, "urn:pix:ispb:1234"))));
    }

    @Test
    void rejectsMultiplePspIdentitiesEvenWhenTheyAreEqual() {
        assertThrows(PspAuthenticationException.class,
                () -> PspClientCertificateIdentityExtractor.extractIspb(List.of(
                        san(6, "urn:pix:ispb:12345678"),
                        san(6, "urn:pix:ispb:12345678"))));
    }

    private static List<?> san(int type, String value) {
        return List.of(type, value);
    }
}
