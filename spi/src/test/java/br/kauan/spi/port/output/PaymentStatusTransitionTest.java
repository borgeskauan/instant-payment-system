package br.kauan.spi.port.output;

import br.kauan.spi.domain.entity.status.PaymentRejectionReason;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentStatusTransitionTest {

    @Test
    void compatibilityConstructorCreatesTransitionWithoutRejectionReason() {
        var transition = new PaymentStatusTransition(
                "payment-1",
                PaymentStatus.WAITING_ACCEPTANCE,
                PaymentStatus.ACCEPTED_AND_SETTLED
        );

        assertThat(transition.rejectionReason()).isNull();
    }

    @Test
    void rejectedTransitionCanCarryInsufficientFundsReason() {
        var transition = new PaymentStatusTransition(
                "payment-1",
                PaymentStatus.WAITING_ACCEPTANCE,
                PaymentStatus.REJECTED,
                PaymentRejectionReason.INSUFFICIENT_FUNDS
        );

        assertThat(transition.rejectionReason()).isEqualTo(PaymentRejectionReason.INSUFFICIENT_FUNDS);
    }

    @Test
    void nonRejectedTransitionCannotCarryARejectionReason() {
        assertThatThrownBy(() -> new PaymentStatusTransition(
                "payment-1",
                PaymentStatus.WAITING_ACCEPTANCE,
                PaymentStatus.ACCEPTED_IN_PROCESS,
                PaymentRejectionReason.INSUFFICIENT_FUNDS
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Rejection reason is only valid for rejected payments");
    }
}
