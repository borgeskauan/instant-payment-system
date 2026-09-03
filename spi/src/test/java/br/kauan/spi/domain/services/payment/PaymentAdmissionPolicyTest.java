package br.kauan.spi.domain.services.payment;

import br.kauan.spi.domain.entity.security.AuthenticatedPaymentRequest;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.domain.services.payment.PaymentAdmissionPolicy.Candidate;
import br.kauan.spi.domain.services.payment.PaymentAdmissionPolicy.Classification;
import br.kauan.spi.domain.services.payment.PaymentAdmissionPolicy.ExistingPayment;
import br.kauan.spi.domain.services.payment.PaymentAdmissionPolicy.PreparedBatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentAdmissionPolicyTest {

    private final PaymentAdmissionPolicy policy = new PaymentAdmissionPolicy();

    @Test
    void preparesOnlyAuthorizedHomogeneousRepresentativesForInsertion() {
        AuthenticatedPaymentRequest first = request(0, "11111111", payment("P1", 100, "11111111", "22222222"));
        AuthenticatedPaymentRequest replay = request(1, "11111111", payment("P1", 100, "11111111", "22222222"));
        AuthenticatedPaymentRequest unauthorized = request(2, "33333333", payment("P2", 100, "11111111", "22222222"));

        PreparedBatch prepared = policy.prepare(List.of(first, replay, unauthorized));

        assertThat(prepared.insertionCandidates()).extracting(Candidate::ordinal).containsExactly(0);
        assertThat(prepared.originalOrdinalsByRepresentative()).containsEntry(0, List.of(0, 1));
        assertThat(prepared.unauthorizedOrdinals()).containsExactly(2);
    }

    @Test
    void classifiesEveryDivergentSameBatchIdentityWithoutPersistence() {
        AuthenticatedPaymentRequest first = request(0, "11111111", payment("P1", 100, "11111111", "22222222"));
        AuthenticatedPaymentRequest divergent = request(1, "11111111", payment("P1", 200, "11111111", "22222222"));
        PreparedBatch prepared = policy.prepare(List.of(first, divergent));

        Classification classification = policy.classifyNonHomogeneousGroups(
                prepared.nonHomogeneousGroups(),
                Map.of()
        );

        assertThat(classification.divergentOrdinals()).containsExactly(0, 1);
        assertThat(classification.unauthorizedOrdinals()).isEmpty();
    }

    @Test
    void expandsAStoredConflictToEveryIdenticalReplayInTheBatch() {
        AuthenticatedPaymentRequest first = request(0, "11111111", payment("P1", 100, "11111111", "22222222"));
        AuthenticatedPaymentRequest replay = request(1, "11111111", payment("P1", 100, "11111111", "22222222"));
        PreparedBatch prepared = policy.prepare(List.of(first, replay));
        Candidate candidate = prepared.insertionCandidates().getFirst();
        ExistingPayment existing = new ExistingPayment("P1", "33333333", candidate.fingerprint().bytes(), candidate.fingerprint().version());

        Classification classification = policy.classifyInsertionConflicts(
                List.of(candidate),
                Map.of("P1", existing),
                prepared.originalOrdinalsByRepresentative()
        );

        assertThat(classification.unauthorizedOrdinals()).containsExactly(0, 1);
        assertThat(classification.divergentOrdinals()).isEmpty();
    }

    @Test
    void rejectsDuplicateSourceOrdinals() {
        AuthenticatedPaymentRequest first = request(0, "11111111", payment("P1", 100, "11111111", "22222222"));
        AuthenticatedPaymentRequest second = request(0, "11111111", payment("P2", 100, "11111111", "22222222"));

        assertThatThrownBy(() -> policy.prepare(List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source ordinals must be unique");
    }

    private AuthenticatedPaymentRequest request(int ordinal, String authenticatedIspb, PaymentTransactionCommand command) {
        return new AuthenticatedPaymentRequest(ordinal, authenticatedIspb, command);
    }

    private PaymentTransactionCommand payment(
            String paymentId,
            long amountCents,
            String payerIspb,
            String receiverIspb
    ) {
        return PaymentTransactionCommand.builder()
                .paymentId(paymentId)
                .amountCents(amountCents)
                .currency("BRL")
                .sender(party(payerIspb))
                .receiver(party(receiverIspb))
                .build();
    }

    private Party party(String ispb) {
        return Party.builder().account(BankAccount.builder().bankCode(ispb).build()).build();
    }
}
