package br.kauan.notificationgateway.delivery;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        RecentNotificationBuffer.Lookup lookup = buffer.lookupAfter("20000001", 0, 3);

        assertThat(lookup.state()).isEqualTo(RecentNotificationBuffer.LookupState.DATA);
        assertThat(lookup.deliveries())
                .extracting(NotificationDelivery::deliveryPosition)
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    void reportsAMissWhenTheFirstRequiredPositionIsNotInMemory() {
        buffer.addAll(List.of(
                delivery(2, "20000001"),
                delivery(3, "20000001")
        ));

        assertThat(buffer.lookupAfter("20000001", 0, 15).state())
                .isEqualTo(RecentNotificationBuffer.LookupState.MISS);
    }

    @Test
    void returnsTheAvailablePrefixWithoutCrossingALaterGap() {
        buffer.addAll(List.of(
                delivery(1, "20000001"),
                delivery(2, "20000001"),
                delivery(4, "20000001")
        ));

        RecentNotificationBuffer.Lookup lookup = buffer.lookupAfter("20000001", 0, 15);

        assertThat(lookup.state()).isEqualTo(RecentNotificationBuffer.LookupState.DATA);
        assertThat(lookup.deliveries())
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

        assertThat(buffer.lookupAfter("20000001", 0, 150).state())
                .isEqualTo(RecentNotificationBuffer.LookupState.MISS);
        assertThat(buffer.lookupAfter("20000001", 50, 200).deliveries())
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

        assertThat(buffer.lookupAfter("20000001", 0, 15).deliveries())
                .extracting(NotificationDelivery::recipientIspb)
                .containsOnly("20000001");
        assertThat(buffer.lookupAfter("20000002", 0, 15).deliveries())
                .extracting(NotificationDelivery::recipientIspb)
                .containsOnly("20000002");
    }

    @Test
    void doesNotClaimTheObservedMaximumAsTheDurableTail() {
        buffer.addAll(List.of(delivery(1, "20000001")));

        assertThat(buffer.lookupAfter("20000001", 1, 15).state())
                .isEqualTo(RecentNotificationBuffer.LookupState.MISS);
    }

    @Test
    void reportsKnownTailOnlyAfterDatabaseConfirmation() {
        buffer.confirmThrough("20000001", 1);

        assertThat(buffer.lookupAfter("20000001", 1, 15).state())
                .isEqualTo(RecentNotificationBuffer.LookupState.KNOWN_TAIL);
    }

    @Test
    void advancesTheConfirmedFrontierOnlyAcrossContiguousBufferedPositions() {
        buffer.confirmThrough("20000001", 0);
        buffer.addAll(List.of(delivery(2, "20000001")));

        assertThat(buffer.lookupAfter("20000001", 0, 15).state())
                .isEqualTo(RecentNotificationBuffer.LookupState.MISS);

        buffer.addAll(List.of(delivery(1, "20000001")));

        assertThat(buffer.lookupAfter("20000001", 2, 15).state())
                .isEqualTo(RecentNotificationBuffer.LookupState.KNOWN_TAIL);
    }

    @Test
    void rejectsNegativeDatabaseFrontiers() {
        assertThatThrownBy(() -> buffer.confirmThrough("20000001", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("confirmed delivery position must not be negative");
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
