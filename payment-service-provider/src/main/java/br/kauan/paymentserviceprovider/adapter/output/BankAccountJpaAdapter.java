package br.kauan.paymentserviceprovider.adapter.output;

import br.kauan.paymentserviceprovider.domain.entity.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.CustomerBankAccount;
import br.kauan.paymentserviceprovider.domain.entity.transfer.BankAccountType;
import br.kauan.paymentserviceprovider.port.output.BankAccountRepository;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

@Repository
public class BankAccountJpaAdapter implements BankAccountRepository {

    private final BankAccountJpaClient bankAccountJpaClient;
    private final BankAccountMapper mapper;

    public BankAccountJpaAdapter(BankAccountJpaClient bankAccountJpaClient, BankAccountMapper mapper) {
        this.bankAccountJpaClient = bankAccountJpaClient;
        this.mapper = mapper;
    }

    @Override
    public Optional<CustomerBankAccount> findById(BankAccountId bankAccountId) {
        var entitiyId = mapper.toEntityId(bankAccountId);

        var bankAccountFound = bankAccountJpaClient.findById(entitiyId);
        if (bankAccountFound.isEmpty()) {
            return createNewAccount(bankAccountId)
                    .map(mapper::toDomain);
        }

        return bankAccountFound.map(mapper::toDomain);
    }

    private Optional<CustomerBankAccountEntity> createNewAccount(BankAccountId bankAccountId) {
        var entityId = mapper.toEntityId(bankAccountId);
        var newAccount = CustomerBankAccountEntity.builder()
                .id(entityId)
                .balance(BigDecimal.valueOf(10000))
                .type(BankAccountType.CHECKING.toString())
                .build();

        var savedEntity = bankAccountJpaClient.save(newAccount);
        return Optional.of(savedEntity);
    }

    @Override
    public void save(CustomerBankAccount bankAccount) {
        var entity = mapper.toEntity(bankAccount);
        bankAccountJpaClient.save(entity);
    }
}
