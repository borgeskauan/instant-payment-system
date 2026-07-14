package br.kauan.spi.adapter.output.kafka;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record NotificationPublication(
        String ispb,
        String payload,
        String communicationId,
        String eventType,
        String paymentId,
        String status,
        String schemaVersion
) {

    private static final String SCHEMA_VERSION = "v1";

    public static NotificationPublication create(
            String ispb,
            String payload,
            String eventType,
            String paymentId,
            String status
    ) {
        return new NotificationPublication(
                ispb,
                payload,
                communicationId(eventType, ispb, paymentId, status),
                eventType,
                paymentId,
                status,
                SCHEMA_VERSION
        );
    }

    private static String communicationId(String eventType, String ispb, String paymentId, String status) {
        String canonical = String.join("|",
                SCHEMA_VERSION,
                eventType,
                ispb,
                paymentId,
                status == null ? "" : status
        );
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return SCHEMA_VERSION + ":" + HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
