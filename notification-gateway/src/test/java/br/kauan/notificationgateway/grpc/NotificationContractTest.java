package br.kauan.notificationgateway.grpc;

import br.kauan.notificationgateway.grpc.proto.NotificationBatch;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationContractTest {

    @Test
    void notificationBatchDoesNotCarryIspb() {
        assertThat(NotificationBatch.getDescriptor().findFieldByName("ispb")).isNull();
    }

    @Test
    void notificationBatchPayloadsIsRepeatedBytes() {
        var payloads = NotificationBatch.getDescriptor().findFieldByName("payloads");

        assertThat(payloads).isNotNull();
        assertThat(payloads.isRepeated()).isTrue();
        assertThat(payloads.getType().name()).isEqualTo("BYTES");
    }
}
