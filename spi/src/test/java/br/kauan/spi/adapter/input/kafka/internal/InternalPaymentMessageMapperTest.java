package br.kauan.spi.adapter.input.kafka.internal;

import br.kauan.pix.internal.v1.PaymentStatus;
import br.kauan.pix.internal.v1.PaymentStatusReport;
import br.kauan.pix.internal.v1.StatusReason;
import br.kauan.spi.domain.entity.status.IncomingStatusReportCommand;
import br.kauan.spi.domain.entity.status.StatusReasonCode;
import br.kauan.spi.domain.entity.status.StatusReportOutcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalPaymentMessageMapperTest {

    private final InternalPaymentMessageMapper mapper = new InternalPaymentMessageMapper();

    @Test
    void mapsAndNormalizesTheSemanticStatusReportIdentity() {
        PaymentStatusReport report = PaymentStatusReport.newBuilder()
                .setPaymentId("E2E-1")
                .setStatus(PaymentStatus.REJECTED)
                .addReasons(reason("AM04", "first description"))
                .addReasons(reason("ab03", "free text is irrelevant"))
                .addReasons(reason("am04", "different description"))
                .build();

        IncomingStatusReportCommand command = mapper.toStatusReport(report);

        assertThat(command.outcome()).isEqualTo(StatusReportOutcome.REJECTED);
        assertThat(command.reasonCodes())
                .extracting(StatusReasonCode::value)
                .containsExactly("AB03", "AM04");
    }

    @Test
    void rejectsAReceiverRejectionWithoutAReasonCode() {
        PaymentStatusReport report = PaymentStatusReport.newBuilder()
                .setPaymentId("E2E-1")
                .setStatus(PaymentStatus.REJECTED)
                .build();

        assertThatThrownBy(() -> mapper.toStatusReport(report))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason code");
    }

    private StatusReason reason(String code, String description) {
        return StatusReason.newBuilder()
                .setCode(code)
                .setDescription(description)
                .build();
    }
}
