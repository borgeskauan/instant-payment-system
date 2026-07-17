package br.kauan.notificationgateway.grpc;

import br.kauan.notificationgateway.grpc.proto.Ack;
import br.kauan.notificationgateway.grpc.proto.ClientMessage;
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

    @Test
    void clientMessageCarriesOnlyAck() {
        assertThat(ClientMessage.getDescriptor().findFieldByName("subscribe")).isNull();
        assertThat(ClientMessage.getDescriptor().findFieldByName("ack").getNumber()).isEqualTo(1);
        assertThat(ClientMessage.getDescriptor().findFieldByName("ack").getMessageType().getFullName())
                .isEqualTo(Ack.getDescriptor().getFullName());
    }
}
