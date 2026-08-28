package br.kauan.spi.application.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationContentSerializerTest {

    @Test
    void serializesNotificationWithOffsetDateTimeGroupHeader() {
        NotificationContentSerializer serializer = serializer();

        var groupHeader = new LinkedHashMap<String, Object>();
        groupHeader.put("MsgId", "MSG-1");
        groupHeader.put("CreDtTm", OffsetDateTime.parse("2026-06-23T20:00:01.123Z"));
        groupHeader.put("NbOfTxs", BigInteger.ONE);

        var notification = new LinkedHashMap<String, Object>();
        notification.put("GrpHdr", groupHeader);

        var serialized = serializer.serialize(notification);

        assertThat(new String(serialized, java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"CreDtTm\":\"2026-06-23T20:00:01.123Z\"");
    }

    private NotificationContentSerializer serializer() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new NotificationContentSerializer(objectMapper);
    }
}
