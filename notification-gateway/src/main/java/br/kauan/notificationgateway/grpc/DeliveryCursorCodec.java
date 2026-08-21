package br.kauan.notificationgateway.grpc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class DeliveryCursorCodec {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String VERSION = "1";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;

    @Autowired
    public DeliveryCursorCodec(
            @Value("${notification-gateway.pull.cursor-secret}") String secret
    ) {
        this(secret.getBytes(StandardCharsets.UTF_8));
    }

    DeliveryCursorCodec(byte[] secret) {
        if (secret.length < 32) {
            throw new IllegalArgumentException("cursor HMAC secret must contain at least 32 bytes");
        }
        this.key = new SecretKeySpec(secret.clone(), HMAC_ALGORITHM);
    }

    String encode(String recipientIspb, long position) {
        if (position <= 0) {
            throw new IllegalArgumentException("delivery position must be positive");
        }
        byte[] payload = (VERSION + ":" + recipientIspb + ":" + position)
                .getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(sign(payload));
    }

    long decodePosition(String cursor, String expectedRecipientIspb) {
        if (cursor == null || cursor.isEmpty()) {
            return 0L;
        }
        try {
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 2) {
                throw new InvalidDeliveryCursorException();
            }
            byte[] payload = DECODER.decode(parts[0]);
            byte[] suppliedSignature = DECODER.decode(parts[1]);
            if (!MessageDigest.isEqual(sign(payload), suppliedSignature)) {
                throw new InvalidDeliveryCursorException();
            }

            String[] fields = new String(payload, StandardCharsets.UTF_8).split(":", -1);
            if (fields.length != 3 || !VERSION.equals(fields[0]) || !expectedRecipientIspb.equals(fields[1])) {
                throw new InvalidDeliveryCursorException();
            }
            long position = Long.parseLong(fields[2]);
            if (position <= 0) {
                throw new InvalidDeliveryCursorException();
            }
            return position;
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
