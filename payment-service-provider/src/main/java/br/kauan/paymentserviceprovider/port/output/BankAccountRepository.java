package br.kauan.paymentserviceprovider.port.output;

import br.kauan.paymentserviceprovider.domain.entity.transfer.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.CustomerBankAccount;

import java.util.Optional;

public interface BankAccountRepository {
    Optional<CustomerBankAccount> findById(BankAccountId bankAccountId);

    void save(CustomerBankAccount bankAccount);
}
