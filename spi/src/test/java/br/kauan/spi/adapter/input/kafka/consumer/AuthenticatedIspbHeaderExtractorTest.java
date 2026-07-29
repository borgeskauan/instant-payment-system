package br.kauan.spi.adapter.input.kafka.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthenticatedIspbHeaderExtractorTest {

    @Test
    void extractsStrictEightDigitIspb() {
        ConsumerRecord<String, byte[]> record = record();
        addHeader(record, "12345678".getBytes(StandardCharsets.UTF_8));

        assertThat(AuthenticatedIspbHeaderExtractor.extract(record)).isEqualTo("12345678");
    }

    @Test
    void rejectsMissingHeader() {
        assertThrows(NotAuthenticatedException.class,
                () -> AuthenticatedIspbHeaderExtractor.extract(record()));
    }

    @Test
    void rejectsDuplicateHeader() {
        ConsumerRecord<String, byte[]> record = record();
        addHeader(record, "12345678".getBytes(StandardCharsets.UTF_8));
        addHeader(record, "12345678".getBytes(StandardCharsets.UTF_8));

        assertThrows(NotAuthenticatedException.class,
                () -> AuthenticatedIspbHeaderExtractor.extract(record));
    }

    @Test
    void rejectsNullHeaderValue() {
        ConsumerRecord<String, byte[]> record = record();
        addHeader(record, null);

        assertThrows(NotAuthenticatedException.class,
                () -> AuthenticatedIspbHeaderExtractor.extract(record));
    }

    @Test
    void rejectsMalformedUtf8() {
        ConsumerRecord<String, byte[]> record = record();
        addHeader(record, new byte[]{(byte) 0xC3, (byte) 0x28});

        assertThrows(NotAuthenticatedException.class,
                () -> AuthenticatedIspbHeaderExtractor.extract(record));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234567", "123456789", "1234ABCD", " 12345678", "12345678 "})
    void rejectsValuesThatAreNotExactlyEightDigits(String value) {
        ConsumerRecord<String, byte[]> record = record();
        addHeader(record, value.getBytes(StandardCharsets.UTF_8));

        assertThrows(NotAuthenticatedException.class,
                () -> AuthenticatedIspbHeaderExtractor.extract(record));
    }

    private static ConsumerRecord<String, byte[]> record() {
        return new ConsumerRecord<>("topic", 0, 0L, "key", new byte[0]);
    }

    private static void addHeader(ConsumerRecord<String, byte[]> record, byte[] value) {
        record.headers().add(AuthenticatedIspbHeaderExtractor.HEADER_NAME, value);
    }
}
