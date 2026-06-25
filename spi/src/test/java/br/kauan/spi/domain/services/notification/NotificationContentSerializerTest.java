package br.kauan.spi.domain.services.notification;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationContentSerializerTest {

    @Test
    void serializesNotificationWithOffsetDateTimeGroupHeader() {
        NotificationContentSerializer serializer = new NotificationContentSerializer();

        var groupHeader = new LinkedHashMap<String, Object>();
        groupHeader.put("MsgId", "MSG-1");
        groupHeader.put("CreDtTm", OffsetDateTime.parse("2026-06-23T20:00:01.123Z"));
        groupHeader.put("NbOfTxs", BigInteger.ONE);

        var notification = new LinkedHashMap<String, Object>();
        notification.put("GrpHdr", groupHeader);

        var serialized = serializer.serialize(notification);

        assertThat(serialized).hasValueSatisfying(json ->
                assertThat(json).contains("\"CreDtTm\":\"2026-06-23T20:00:01.123Z\""));
    }
}
