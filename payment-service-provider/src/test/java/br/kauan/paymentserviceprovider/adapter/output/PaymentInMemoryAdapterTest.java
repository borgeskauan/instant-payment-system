package br.kauan.paymentserviceprovider.adapter.output;

import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccount;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentInMemoryAdapterTest {

    private final PaymentInMemoryAdapter adapter = new PaymentInMemoryAdapter();

    @Test
    void newIncomingRequestIsStoredAndReturnedForAcceptance() {
        PaymentTransaction payment = payment("E2E-1", "10000001", "20000001", "10.00");

        var result = adapter.storeAndClassifyIncomingRequests(List.of(payment));

        assertThat(result.acceptedPaymentRequests()).containsExactly(payment);
        assertThat(result.divergentPaymentRequests()).isEmpty();
        assertThat(adapter.findAllByIds(List.of("E2E-1"))).containsExactly(payment);
    }

    @Test
    void identicalReplayIsReturnedForAcceptanceWithoutOverwriting() {
        PaymentTransaction payment = payment("E2E-1", "10000001", "20000001", "10.00");
        adapter.storeAndClassifyIncomingRequests(List.of(payment));

        var result = adapter.storeAndClassifyIncomingRequests(List.of(payment));

        assertThat(result.acceptedPaymentRequests()).containsExactly(payment);
        assertThat(result.divergentPaymentRequests()).isEmpty();
    }

    @Test
    void divergentReplayIsReturnedAsDivergentAndDoesNotOverwriteExistingPayment() {
        PaymentTransaction original = payment("E2E-1", "10000001", "20000001", "10.00");
        PaymentTransaction divergent = payment("E2E-1", "10000001", "30000001", "10.00");
        adapter.storeAndClassifyIncomingRequests(List.of(original));

        var result = adapter.storeAndClassifyIncomingRequests(List.of(divergent));

        assertThat(result.acceptedPaymentRequests()).isEmpty();
        assertThat(result.divergentPaymentRequests()).containsExactly(divergent);
        assertThat(adapter.findAllByIds(List.of("E2E-1"))).containsExactly(original);
    }

    @Test
    void sameBatchIdenticalRequestsReturnOnlyFirstLogicalAcceptance() {
        PaymentTransaction first = payment("E2E-1", "10000001", "20000001", "10.00");
        PaymentTransaction repeated = payment("E2E-1", "10000001", "20000001", "10.00");

        var result = adapter.storeAndClassifyIncomingRequests(List.of(first, repeated));

        assertThat(result.acceptedPaymentRequests()).containsExactly(first);
        assertThat(result.divergentPaymentRequests()).isEmpty();
    }

    @Test
    void sameBatchDivergentRequestsReturnEveryOriginalRecordAsDivergentAndStoreNone() {
        PaymentTransaction first = payment("E2E-1", "10000001", "20000001", "10.00");
        PaymentTransaction second = payment("E2E-1", "10000001", "30000001", "10.00");

        var result = adapter.storeAndClassifyIncomingRequests(List.of(first, second));

        assertThat(result.acceptedPaymentRequests()).isEmpty();
        assertThat(result.divergentPaymentRequests()).containsExactly(first, second);
        assertThat(adapter.findAllByIds(List.of("E2E-1"))).isEmpty();
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
