package br.kauan.paymentserviceprovider.adapter.output.bankaccount;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BankAccountJpaClient extends JpaRepository<CustomerBankAccountEntity, CustomerBankAccountEntity.CustomerBankAccountId> {
    List<CustomerBankAccountEntity> findByCustomerId(String customerId);
}
