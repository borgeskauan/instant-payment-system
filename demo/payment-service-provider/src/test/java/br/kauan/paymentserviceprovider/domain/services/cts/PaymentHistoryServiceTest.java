package br.kauan.paymentserviceprovider.domain.services.cts;

import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccount;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.customer.CustomerBankAccount;
import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentDirection;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentLifecycleStatus;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import br.kauan.paymentserviceprovider.state.PaymentStore;
import br.kauan.paymentserviceprovider.state.PspStateStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentHistoryServiceTest {

    @Test
    void exposesTheCustomerPerspectiveWithoutLeakingInternalStatusNames() {
        BankAccountId localAccountId = accountId("local", "10000001");
        PaymentTransaction payment = PaymentTransaction.builder()
                .paymentId("E2E-1")
                .amount(new BigDecimal("10.00"))
                .currency("BRL")
                .sender(party("Alice", "alice@pix", localAccountId))
                .receiver(party("Bob", "bob@pix", accountId("remote", "20000001")))
                .build();
        PspStateStore stateStore = mock(PspStateStore.class);
        when(stateStore.findAccountByCustomerId("customer-1")).thenReturn(java.util.Optional.of(
                CustomerBankAccount.builder()
                        .customerId("customer-1")
                        .account(BankAccount.builder().id(localAccountId).build())
                        .build()
        ));
        PaymentStore paymentStore = new PaymentStore();
        paymentStore.saveAll(List.of(payment));
        PaymentHistoryService service = new PaymentHistoryService(stateStore, paymentStore);

        var result = service.findByCustomerId("customer-1");

        assertThat(result).singleElement().satisfies(summary -> {
            assertThat(summary.paymentId()).isEqualTo("E2E-1");
            assertThat(summary.direction()).isEqualTo(PaymentDirection.OUTGOING);
            assertThat(summary.counterparty().name()).isEqualTo("Bob");
            assertThat(summary.counterparty().pixKey()).isEqualTo("bob@pix");
            assertThat(summary.status()).isEqualTo(PaymentLifecycleStatus.PROCESSING);
        });
    }

    private static Party party(String name, String pixKey, BankAccountId accountId) {
        return Party.builder()
                .name(name)
                .pixKey(pixKey)
                .account(BankAccount.builder().id(accountId).build())
                .build();
    }

    private static BankAccountId accountId(String account, String bankCode) {
        return BankAccountId.builder()
                .accountNumber(account)
                .agencyNumber("0001")
                .bankCode(bankCode)
                .build();
    }
}
