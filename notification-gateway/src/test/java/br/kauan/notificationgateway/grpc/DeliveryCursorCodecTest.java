package br.kauan.notificationgateway.grpc;

import br.kauan.notificationgateway.kafka.NotificationLog;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryCursorCodecTest {

    private static final byte[] SECRET =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    void springCreatesCodecFromConfiguredStableSecret() {
        new ApplicationContextRunner()
                .withPropertyValues("notification-gateway.pull.cursor-secret=0123456789abcdef0123456789abcdef")
                .withBean(DeliveryCursorCodec.class)
                .run(context -> assertThat(context).hasSingleBean(DeliveryCursorCodec.class));
    }

    @Test
    void roundTripsThePspTopicPartitionAndLastExaminedOffsetAcrossRestart() {
        DeliveryCursorCodec issuer = new DeliveryCursorCodec(SECRET);
        DeliveryCursor issued = new DeliveryCursor("20000001", NotificationLog.GENERATION, 3, 250L);

        String cursor = issuer.encode(issued);

        assertThat(new DeliveryCursorCodec(SECRET).decode(cursor, "20000001", 3)).isEqualTo(issued);
    }

    @Test
    void emptyCursorStartsBeforeTheCurrentRetainedLog() {
        DeliveryCursorCodec codec = new DeliveryCursorCodec(SECRET);

        assertThat(codec.decode("", "20000001", 3))
                .isEqualTo(new DeliveryCursor("20000001", NotificationLog.GENERATION, 3, -1L));
    }

    @Test
    void rejectsTamperingAnotherPspAndAnotherPartition() {
        DeliveryCursorCodec codec = new DeliveryCursorCodec(SECRET);
        String cursor = codec.encode(new DeliveryCursor("20000001", NotificationLog.GENERATION, 3, 250L));
        String tampered = cursor.substring(0, cursor.length() - 1)
                + (cursor.endsWith("A") ? "B" : "A");

        assertThatThrownBy(() -> codec.decode(tampered, "20000001", 3))
                .isInstanceOf(InvalidDeliveryCursorException.class);
        assertThatThrownBy(() -> codec.decode(cursor, "20000002", 3))
                .isInstanceOf(InvalidDeliveryCursorException.class);
        assertThatThrownBy(() -> codec.decode(cursor, "20000001", 4))
                .isInstanceOf(InvalidDeliveryCursorException.class);
    }

    @Test
    void requiresStableSecretWithAtLeast256Bits() {
        assertThatThrownBy(() -> new DeliveryCursorCodec("too-short".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32 bytes");
    }
}
