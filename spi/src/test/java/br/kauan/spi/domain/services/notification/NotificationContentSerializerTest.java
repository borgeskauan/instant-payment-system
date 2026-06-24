package br.kauan.spi.domain.services.notification;

import br.kauan.spi.adapter.input.dtos.pacs.commons.GroupHeader;
import br.kauan.spi.adapter.input.dtos.pacs.pacs008.FIToFICustomerCreditTransfer;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationContentSerializerTest {

    @Test
    void serializesPacsNotificationWithOffsetDateTimeGroupHeader() {
        NotificationContentSerializer serializer = new NotificationContentSerializer();

        var notification = FIToFICustomerCreditTransfer.builder()
                .groupHeader(GroupHeader.builder()
                        .messageId("MSG-1")
                        .creationTimestamp(OffsetDateTime.parse("2026-06-23T20:00:01.123Z"))
                        .numberOfTransactions(BigInteger.ONE)
                        .build())
                .creditTransferTransactions(List.of())
                .build();

        var serialized = serializer.serialize(notification);

        assertThat(serialized).hasValueSatisfying(json ->
                assertThat(json).contains("\"CreDtTm\":\"2026-06-23T20:00:01.123Z\""));
    }
}
