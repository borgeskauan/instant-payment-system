package br.kauan.notificationgateway.grpc;

import br.kauan.notificationgateway.grpc.proto.Notification;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationContractTest {

    @Test
    void notificationDoesNotCarryIspb() {
        assertThat(Notification.getDescriptor().findFieldByName("ispb")).isNull();
    }
}
