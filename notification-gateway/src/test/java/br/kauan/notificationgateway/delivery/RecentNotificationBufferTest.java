package br.kauan.notificationgateway.delivery;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecentNotificationBufferTest {

    private final RecentNotificationBuffer buffer = new RecentNotificationBuffer();

    @Test
    void returnsAtMostTheRequestedContiguousPrefixInPositionOrder() {
        buffer.addAll(List.of(
                delivery(3, "20000001"),
                delivery(1, "20000001"),
                delivery(2, "20000001"),
                delivery(4, "20000001")
        ));

        assertThat(buffer.findContiguousAfter("20000001", 0, 3))
                .extracting(NotificationDelivery::deliveryPosition)
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    void reportsAMissWhenTheFirstRequiredPositionIsNotInMemory() {
        buffer.addAll(List.of(
                delivery(2, "20000001"),
                delivery(3, "20000001")
        ));

        assertThat(buffer.findContiguousAfter("20000001", 0, 15)).isEmpty();
    }

    @Test
    void returnsTheAvailablePrefixWithoutCrossingALaterGap() {
        buffer.addAll(List.of(
                delivery(1, "20000001"),
                delivery(2, "20000001"),
                delivery(4, "20000001")
        ));

        assertThat(buffer.findContiguousAfter("20000001", 0, 15))
                .extracting(NotificationDelivery::deliveryPosition)
                .containsExactly(1L, 2L);
    }

    @Test
    void retainsOnlyTheMostRecentOneHundredAndFiftyPositionsPerPsp() {
        List<NotificationDelivery> deliveries = new ArrayList<>();
        for (long position = 200; position >= 1; position--) {
            deliveries.add(delivery(position, "20000001"));
        }
        buffer.addAll(deliveries);

        assertThat(buffer.findContiguousAfter("20000001", 0, 150)).isEmpty();
        assertThat(buffer.findContiguousAfter("20000001", 50, 200))
                .hasSize(150)
                .extracting(NotificationDelivery::deliveryPosition)
                .startsWith(51L)
                .endsWith(200L);
    }

    @Test
    void isolatesRecipientWindows() {
        buffer.addAll(List.of(
                delivery(1, "20000001"),
                delivery(1, "20000002")
        ));

        assertThat(buffer.findContiguousAfter("20000001", 0, 15))
                .extracting(NotificationDelivery::recipientIspb)
                .containsOnly("20000001");
        assertThat(buffer.findContiguousAfter("20000002", 0, 15))
                .extracting(NotificationDelivery::recipientIspb)
                .containsOnly("20000002");
    }

    private NotificationDelivery delivery(long position, String recipientIspb) {
        return new NotificationDelivery(
                position,
                recipientIspb + ":" + position,
                recipientIspb,
                Long.toString(position).getBytes(StandardCharsets.UTF_8)
        );
    }
}
