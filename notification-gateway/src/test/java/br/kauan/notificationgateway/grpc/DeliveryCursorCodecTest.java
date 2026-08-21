package br.kauan.notificationgateway.grpc;

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
                .withPropertyValues(
                        "notification-gateway.pull.cursor-secret=0123456789abcdef0123456789abcdef"
                )
                .withBean(DeliveryCursorCodec.class)
                .run(context -> assertThat(context).hasSingleBean(DeliveryCursorCodec.class));
    }

    @Test
    void roundTripsPositionForAuthenticatedPspAcrossCodecRestart() {
        DeliveryCursorCodec issuer = new DeliveryCursorCodec(SECRET);
        String cursor = issuer.encode("20000001", 250L);

        DeliveryCursorCodec restarted = new DeliveryCursorCodec(SECRET);

        assertThat(restarted.decodePosition(cursor, "20000001")).isEqualTo(250L);
    }

    @Test
    void emptyCursorStartsBeforeTheFirstDelivery() {
        DeliveryCursorCodec codec = new DeliveryCursorCodec(SECRET);

        assertThat(codec.decodePosition("", "20000001")).isZero();
    }

    @Test
    void rejectsTamperedCursor() {
        DeliveryCursorCodec codec = new DeliveryCursorCodec(SECRET);
        String cursor = codec.encode("20000001", 250L);
        String tampered = cursor.substring(0, cursor.length() - 1)
                + (cursor.endsWith("A") ? "B" : "A");

        assertThatThrownBy(() -> codec.decodePosition(tampered, "20000001"))
                .isInstanceOf(InvalidDeliveryCursorException.class)
                .hasMessage("invalid delivery cursor");
    }

    @Test
    void rejectsCursorIssuedForAnotherPsp() {
        DeliveryCursorCodec codec = new DeliveryCursorCodec(SECRET);
        String cursor = codec.encode("20000001", 250L);

        assertThatThrownBy(() -> codec.decodePosition(cursor, "20000002"))
                .isInstanceOf(InvalidDeliveryCursorException.class)
                .hasMessage("invalid delivery cursor");
    }

    @Test
    void requiresStableSecretWithAtLeast256Bits() {
        assertThatThrownBy(() -> new DeliveryCursorCodec("too-short".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32 bytes");
    }
}
