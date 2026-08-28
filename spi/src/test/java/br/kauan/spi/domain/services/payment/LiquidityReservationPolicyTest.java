package br.kauan.spi.domain.services.payment;

import br.kauan.spi.domain.entity.security.AuthenticatedPaymentRequest;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.domain.services.payment.LiquidityReservationPolicy.ReservationOutcome;
import br.kauan.spi.domain.services.payment.LiquidityReservationPolicy.ReservationPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiquidityReservationPolicyTest {

    private final LiquidityReservationPolicy policy = new LiquidityReservationPolicy();

    @Test
    void reservesInSourceOrderWithoutRejectingAFeasibleLaterPayment() {
        AuthenticatedPaymentRequest first = request(0, "P1", 80);
        AuthenticatedPaymentRequest tooLarge = request(1, "P2", 50);
        AuthenticatedPaymentRequest laterSmall = request(2, "P3", 10);

        ReservationPlan plan = policy.plan(
                policy.prepare(List.of(first, tooLarge, laterSmall)),
                Map.of("11111111", 100L)
        );

        assertThat(plan.outcomes()).extracting(ReservationOutcome::reserved).containsExactly(true, false, true);
        assertThat(plan.debitsByPayer()).containsExactly(Map.entry("11111111", 90L));
        assertThat(plan.insufficientPaymentIds()).containsExactly("P2");
    }

    @Test
    void requiresEveryPayerBalanceBeforeMakingReservations() {
        assertThatThrownBy(() -> policy.plan(policy.prepare(List.of(request(0, "P1", 10))), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Required participant balance is missing");
    }

    private AuthenticatedPaymentRequest request(int ordinal, String paymentId, long amountCents) {
        PaymentTransactionCommand command = PaymentTransactionCommand.builder()
                .paymentId(paymentId)
                .amountCents(amountCents)
                .currency("BRL")
                .sender(party("11111111"))
                .receiver(party("22222222"))
                .build();
        return new AuthenticatedPaymentRequest(ordinal, "11111111", command);
    }

    private Party party(String ispb) {
        return Party.builder().account(BankAccount.builder().bankCode(ispb).build()).build();
    }
}
