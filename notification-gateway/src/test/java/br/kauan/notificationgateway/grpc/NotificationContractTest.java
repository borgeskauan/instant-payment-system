package br.kauan.notificationgateway.grpc;

import br.kauan.notificationgateway.grpc.proto.NotificationProto;
import com.google.protobuf.Descriptors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationContractTest {

    @Test
    void exposesOnlyUnaryPullWithOpaqueCursor() {
        Descriptors.ServiceDescriptor service = NotificationProto.getDescriptor()
                .findServiceByName("NotificationGateway");

        assertThat(service.getMethods()).singleElement().satisfies(method -> {
            assertThat(method.getName()).isEqualTo("PullNotifications");
            assertThat(method.isClientStreaming()).isFalse();
            assertThat(method.isServerStreaming()).isFalse();
        });

        Descriptors.Descriptor request = NotificationProto.getDescriptor().findMessageTypeByName("PullRequest");
        assertThat(request.getFields()).extracting(Descriptors.FieldDescriptor::getName)
                .containsExactly("cursor");

        Descriptors.Descriptor response = NotificationProto.getDescriptor().findMessageTypeByName("PullResponse");
        assertThat(response.getFields()).extracting(Descriptors.FieldDescriptor::getName)
                .containsExactly("notifications", "next_cursor");
    }

    @Test
    void notificationContainsOnlyOpaqueBusinessPayload() {
        Descriptors.Descriptor notification = NotificationProto.getDescriptor()
                .findMessageTypeByName("Notification");

        assertThat(notification.getFields()).singleElement().satisfies(payload -> {
            assertThat(payload.getName()).isEqualTo("payload");
            assertThat(payload.getType()).isEqualTo(Descriptors.FieldDescriptor.Type.BYTES);
        });
    }
}
