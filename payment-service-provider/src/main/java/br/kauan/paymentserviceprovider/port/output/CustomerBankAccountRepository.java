package br.kauan.paymentserviceprovider.port.output;

import br.kauan.paymentserviceprovider.domain.entity.customer.CustomerBankAccount;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountId;

import java.util.Collection;
import java.util.List;

public interface CustomerBankAccountRepository {
    List<CustomerBankAccount> findAllByIds(Collection<BankAccountId> bankAccountIds);

    List<CustomerBankAccount> findAllByCustomerIds(Collection<String> customerIds);

    void saveAll(Collection<CustomerBankAccount> bankAccounts);
}
