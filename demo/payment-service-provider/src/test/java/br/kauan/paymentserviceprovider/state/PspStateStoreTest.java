package br.kauan.paymentserviceprovider.state;

import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccount;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.customer.Customer;
import br.kauan.paymentserviceprovider.domain.entity.customer.CustomerBankAccount;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PspStateStoreTest {

    private final PspStateStore store = new PspStateStore();

    @Test
    void balanceChangesAreAppliedToKnownAccounts() {
        CustomerBankAccount account = addCustomer("customer-1", "account-1", "100.00");

        store.applyBalanceDeltas(Map.of(account.getAccount().getId(), new BigDecimal("25.00")));

        assertThat(account.getBalance()).isEqualByComparingTo("125.00");
    }

    @Test
    void missingAccountFailsBeforeAnyBalanceIsChanged() {
        CustomerBankAccount knownAccount = addCustomer("customer-1", "account-1", "100.00");
        BankAccountId missingAccount = accountId("missing-account");

        assertThatThrownBy(() -> store.applyBalanceDeltas(Map.of(
                knownAccount.getAccount().getId(), new BigDecimal("25.00"),
                missingAccount, new BigDecimal("10.00")
        ))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Local account not found");

        assertThat(knownAccount.getBalance()).isEqualByComparingTo("100.00");
    }

    private CustomerBankAccount addCustomer(String customerId, String accountNumber, String balance) {
        Customer customer = Customer.builder().id(customerId).taxId(customerId + "-tax").name(customerId).build();
        CustomerBankAccount account = CustomerBankAccount.builder()
                .customerId(customerId)
                .account(BankAccount.builder().id(accountId(accountNumber)).build())
                .balance(new BigDecimal(balance))
                .build();
        store.addCustomer(customer, account);
        return account;
    }

    private BankAccountId accountId(String accountNumber) {
        return BankAccountId.builder()
                .bankCode("12345678")
                .agencyNumber("0001")
                .accountNumber(accountNumber)
                .build();
    }
}
