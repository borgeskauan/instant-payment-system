package br.kauan.notificationgateway.grpc;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

@Component
public final class DeliveryCursorCodec {

    public static final String TOPIC_GENERATION = "psp-notifications-v1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String VERSION = "1";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;

    @Autowired
    public DeliveryCursorCodec(@Value("${notification-gateway.pull.cursor-secret}") String secret) {
        this(secret.getBytes(StandardCharsets.UTF_8));
    }

    DeliveryCursorCodec(byte[] secret) {
        if (secret.length < 32) {
            throw new IllegalArgumentException("cursor HMAC secret must contain at least 32 bytes");
        }
        this.key = new SecretKeySpec(secret.clone(), HMAC_ALGORITHM);
    }

    String encode(DeliveryCursor cursor) {
        if (cursor.lastExaminedOffset() < 0
                || cursor.partition() < 0
                || !TOPIC_GENERATION.equals(cursor.topicGeneration())) {
            throw new IllegalArgumentException("cannot issue cursor for an invalid notification log position");
        }
        byte[] payload = String.join(
                        ":",
                        VERSION,
                        cursor.topicGeneration(),
                        cursor.recipientIspb(),
                        Integer.toString(cursor.partition()),
                        Long.toString(cursor.lastExaminedOffset())
                )
                .getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(sign(payload));
    }

    DeliveryCursor decode(String encoded, String expectedRecipientIspb, int expectedPartition) {
        if (encoded == null || encoded.isEmpty()) {
            return new DeliveryCursor(expectedRecipientIspb, TOPIC_GENERATION, expectedPartition, -1L);
        }
        try {
            String[] parts = encoded.split("\\.", -1);
            if (parts.length != 2) {
                throw new InvalidDeliveryCursorException();
            }
            byte[] payload = DECODER.decode(parts[0]);
            byte[] suppliedSignature = DECODER.decode(parts[1]);
            if (!MessageDigest.isEqual(sign(payload), suppliedSignature)) {
                throw new InvalidDeliveryCursorException();
            }
            String[] fields = new String(payload, StandardCharsets.UTF_8).split(":", -1);
            if (fields.length != 5
                    || !VERSION.equals(fields[0])
                    || !TOPIC_GENERATION.equals(fields[1])
                    || !expectedRecipientIspb.equals(fields[2])) {
                throw new InvalidDeliveryCursorException();
            }
            int partition = Integer.parseInt(fields[3]);
            long offset = Long.parseLong(fields[4]);
            if (partition != expectedPartition || offset < 0) {
                throw new InvalidDeliveryCursorException();
            }
            return new DeliveryCursor(fields[2], fields[1], partition, offset);
        } catch (InvalidDeliveryCursorException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new InvalidDeliveryCursorException();
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("cannot initialize cursor HMAC", exception);
        }
    }
}
