package br.kauan.paymentserviceprovider.adapter.output.bankaccount;

import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.customer.CustomerBankAccount;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountType;
import br.kauan.paymentserviceprovider.port.output.CustomerBankAccountRepository;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public class CustomerBankAccountJpaAdapter implements CustomerBankAccountRepository {

    private final BankAccountJpaClient bankAccountJpaClient;
    private final BankAccountMapper mapper;

    public CustomerBankAccountJpaAdapter(BankAccountJpaClient bankAccountJpaClient, BankAccountMapper mapper) {
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

    @Override
    public void save(CustomerBankAccount bankAccount) {
        var entity = mapper.toEntity(bankAccount);
        bankAccountJpaClient.save(entity);
    }

    @Override
    public List<CustomerBankAccount> findByCustomerId(String customerId) {
        var entities = bankAccountJpaClient.findByCustomerId(customerId);
        return entities.stream()
                .map(mapper::toDomain)
                .toList();
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
}
