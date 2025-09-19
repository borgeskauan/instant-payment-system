package br.kauan.paymentserviceprovider.adapter.output;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BankAccountJpaClient extends JpaRepository<CustomerBankAccountEntity, CustomerBankAccountEntity.CustomerBankAccountId> {
}
