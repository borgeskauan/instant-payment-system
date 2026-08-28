package br.kauan.paymentserviceprovider.adapter.output.bankaccount;

import br.kauan.paymentserviceprovider.domain.entity.customer.CustomerBankAccount;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccount;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountType;
import org.springframework.stereotype.Component;

@Component
public class BankAccountMapper {

    public CustomerBankAccount toDomain(CustomerBankAccountEntity entity) {
        var entityId = entity.getId();

        var bankAccountId = BankAccountId.builder()
                .accountNumber(entityId.getAccountNumber())
                .agencyNumber(entityId.getAgencyNumber())
                .bankCode(entityId.getBankCode())
                .build();

        var bankAccount = BankAccount.builder()
                .id(bankAccountId)
                .type(BankAccountType.valueOf(entity.getType()))
                .build();

        return CustomerBankAccount.builder()
                .customerId(entity.getCustomerId())
                .account(bankAccount)
                .balance(entity.getBalance())
                .build();
    }

    public CustomerBankAccountEntity toEntity(CustomerBankAccount bankAccount) {
        var accountId = bankAccount.getAccount().getId();

        var id = CustomerBankAccountEntity.CustomerBankAccountId.builder()
                .accountNumber(accountId.getAccountNumber())
                .agencyNumber(accountId.getAgencyNumber())
                .bankCode(accountId.getBankCode())
                .build();

        return CustomerBankAccountEntity.builder()
                .id(id)
                .customerId(bankAccount.getCustomerId())
                .type(bankAccount.getAccount().getType().name())
                .balance(bankAccount.getBalance())
                .build();
    }

    public CustomerBankAccountEntity.CustomerBankAccountId toEntityId(BankAccountId bankAccountId) {
        return CustomerBankAccountEntity.CustomerBankAccountId.builder()
                .accountNumber(bankAccountId.getAccountNumber())
                .agencyNumber(bankAccountId.getAgencyNumber())
                .bankCode(bankAccountId.getBankCode())
                .build();
    }
}
