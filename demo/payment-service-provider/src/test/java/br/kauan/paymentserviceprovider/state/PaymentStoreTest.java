package br.kauan.paymentserviceprovider.state;

import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccount;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentStoreTest {

    private final PaymentStore store = new PaymentStore();

    @Test
    void newIncomingPaymentIsStoredAndAccepted() {
        PaymentTransaction payment = payment("E2E-1", "10000001", "20000001", "10.00");

        var result = store.storeAndClassifyIncoming(List.of(payment));

        assertThat(result.acceptedPayments()).containsExactly(payment);
        assertThat(result.divergentPayments()).isEmpty();
        assertThat(store.findAllByIds(List.of("E2E-1"))).containsExactly(payment);
    }

    @Test
    void identicalReplayIsAcceptedWithoutOverwritingThePayment() {
        PaymentTransaction payment = payment("E2E-1", "10000001", "20000001", "10.00");
        store.storeAndClassifyIncoming(List.of(payment));

        var result = store.storeAndClassifyIncoming(List.of(payment));

        assertThat(result.acceptedPayments()).containsExactly(payment);
        assertThat(result.divergentPayments()).isEmpty();
    }

    @Test
    void divergentReplayIsRejectedWithoutOverwritingThePayment() {
        PaymentTransaction original = payment("E2E-1", "10000001", "20000001", "10.00");
        PaymentTransaction divergent = payment("E2E-1", "10000001", "30000001", "10.00");
        store.storeAndClassifyIncoming(List.of(original));

        var result = store.storeAndClassifyIncoming(List.of(divergent));

        assertThat(result.acceptedPayments()).isEmpty();
        assertThat(result.divergentPayments()).containsExactly(divergent);
        assertThat(store.findAllByIds(List.of("E2E-1"))).containsExactly(original);
    }

    @Test
    void identicalRecordsInOneBatchCreateOneLogicalAcceptance() {
        PaymentTransaction first = payment("E2E-1", "10000001", "20000001", "10.00");
        PaymentTransaction repeated = payment("E2E-1", "10000001", "20000001", "10.00");

        var result = store.storeAndClassifyIncoming(List.of(first, repeated));

        assertThat(result.acceptedPayments()).containsExactly(first);
        assertThat(result.divergentPayments()).isEmpty();
    }

    @Test
    void divergentRecordsInOneBatchAreRejectedAndNotStored() {
        PaymentTransaction first = payment("E2E-1", "10000001", "20000001", "10.00");
        PaymentTransaction second = payment("E2E-1", "10000001", "30000001", "10.00");

        var result = store.storeAndClassifyIncoming(List.of(first, second));

        assertThat(result.acceptedPayments()).isEmpty();
        assertThat(result.divergentPayments()).containsExactly(first, second);
        assertThat(store.findAllByIds(List.of("E2E-1"))).isEmpty();
    }

    private static PaymentTransaction payment(String paymentId, String senderBankCode, String receiverBankCode, String amount) {
        return PaymentTransaction.builder()
                .paymentId(paymentId)
                .amount(new BigDecimal(amount))
                .currency("BRL")
                .description("test")
                .sender(party("sender", senderBankCode))
                .receiver(party("receiver", receiverBankCode))
                .build();
    }

    private static Party party(String name, String bankCode) {
        return Party.builder()
                .name(name)
                .taxId(name + "-tax-id")
                .account(BankAccount.builder()
                        .id(BankAccountId.builder()
                                .accountNumber(name + "-account")
                                .agencyNumber("0001")
                                .bankCode(bankCode)
                                .build())
                        .build())
                .pixKey(name + "@pix")
                .build();
    }
}
