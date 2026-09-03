package br.kauan.spi.domain.entity.status;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncomingStatusReportCommandTest {

    @Test
    void rejectedOutcomeRequiresAReasonCode() {
        assertThatThrownBy(() -> new IncomingStatusReportCommand(
                "E2E-1", StatusReportOutcome.REJECTED, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason code");
    }

    @Test
    void acceptedOutcomeMayCarryNormalizedCodes() {
        var command = new IncomingStatusReportCommand(
                "E2E-1",
                StatusReportOutcome.ACCEPTED,
                List.of(StatusReasonCode.of("ab03"))
        );

        assertThat(command.reasonCodes())
                .extracting(StatusReasonCode::value)
                .containsExactly("AB03");
    }
}
