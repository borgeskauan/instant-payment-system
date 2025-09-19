package br.kauan.paymentserviceprovider.adapter.output;

import br.kauan.paymentserviceprovider.domain.entity.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.CustomerBankAccount;
import br.kauan.paymentserviceprovider.domain.entity.transfer.BankAccountType;
import org.springframework.stereotype.Component;

@Component
public class BankAccountMapper {

    public CustomerBankAccount toDomain(CustomerBankAccountEntity entity) {
        var entityId = entity.getId();

        return CustomerBankAccount.builder()
                .accountNumber(entityId.getAccountNumber())
                .agencyNumber(entityId.getAgencyNumber())
                .bankCode(entityId.getBankCode())
                .type(BankAccountType.valueOf(entity.getType()))
                .balance(entity.getBalance())
                .build();
    }

    public CustomerBankAccountEntity toEntity(CustomerBankAccount bankAccount) {
        var id = CustomerBankAccountEntity.CustomerBankAccountId.builder()
                .accountNumber(bankAccount.getAccountNumber())
                .agencyNumber(bankAccount.getAgencyNumber())
                .bankCode(bankAccount.getBankCode())
                .build();

        return CustomerBankAccountEntity.builder()
                .id(id)
                .type(bankAccount.getType().name())
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
