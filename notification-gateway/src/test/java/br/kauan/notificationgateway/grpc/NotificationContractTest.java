package br.kauan.notificationgateway.grpc;

import br.kauan.notificationgateway.grpc.proto.Notification;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationContractTest {

    @Test
    void notificationDoesNotCarryIspb() {
        assertThat(Notification.getDescriptor().findFieldByName("ispb")).isNull();
    }

    @Test
    void notificationPayloadIsBytesAndDeliveryIdIsPresent() {
        var deliveryId = Notification.getDescriptor().findFieldByName("delivery_id");
        var payload = Notification.getDescriptor().findFieldByName("payload");

        assertThat(deliveryId).isNotNull();
        assertThat(deliveryId.getType().name()).isEqualTo("STRING");
        assertThat(payload).isNotNull();
        assertThat(payload.isRepeated()).isFalse();
        assertThat(payload.getType().name()).isEqualTo("BYTES");
    }
}
