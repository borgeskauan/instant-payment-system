package br.kauan.paymentserviceprovider.port.output;

import br.kauan.paymentserviceprovider.domain.entity.CustomerBankAccount;
import br.kauan.paymentserviceprovider.domain.entity.transfer.BankAccountId;

import java.util.List;
import java.util.Optional;

public interface CustomerBankAccountRepository {
    Optional<CustomerBankAccount> findById(BankAccountId bankAccountId);

    void save(CustomerBankAccount bankAccount);

    List<CustomerBankAccount> findByCustomerId(String customerId);
}
