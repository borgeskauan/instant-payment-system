package br.kauan.spi.adapter.input.kafka.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.regex.Pattern;

final class AuthenticatedIspbHeaderExtractor {

    static final String HEADER_NAME = "authenticated-ispb";
    private static final Pattern ISPB_PATTERN = Pattern.compile("\\d{8}");

    private AuthenticatedIspbHeaderExtractor() {
    }

    static String extract(ConsumerRecord<?, ?> record) {
        Iterator<Header> headers = record.headers().headers(HEADER_NAME).iterator();
        if (!headers.hasNext()) {
            throw notAuthenticated("authenticated-ispb header is required");
        }

        Header header = headers.next();
        if (headers.hasNext()) {
            throw notAuthenticated("authenticated-ispb header must occur exactly once");
        }
        if (header.value() == null) {
            throw notAuthenticated("authenticated-ispb header value is required");
        }

        String authenticatedIspb = decodeStrictly(header.value());
        if (!ISPB_PATTERN.matcher(authenticatedIspb).matches()) {
            throw notAuthenticated("authenticated-ispb header must contain exactly 8 digits");
        }
        return authenticatedIspb;
    }

    private static String decodeStrictly(byte[] value) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new NotAuthenticatedException("authenticated-ispb header must be valid UTF-8", e);
        }
    }

    private static NotAuthenticatedException notAuthenticated(String message) {
        return new NotAuthenticatedException(message);
    }
}
