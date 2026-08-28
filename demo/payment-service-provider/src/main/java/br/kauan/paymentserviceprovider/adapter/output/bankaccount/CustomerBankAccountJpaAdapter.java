package br.kauan.paymentserviceprovider.adapter.output.bankaccount;

import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.customer.CustomerBankAccount;
import br.kauan.paymentserviceprovider.domain.entity.commons.BankAccountType;
import br.kauan.paymentserviceprovider.port.output.CustomerBankAccountRepository;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

@Repository
public class CustomerBankAccountJpaAdapter implements CustomerBankAccountRepository {

    private final BankAccountJpaClient bankAccountJpaClient;
    private final BankAccountMapper mapper;

    public CustomerBankAccountJpaAdapter(BankAccountJpaClient bankAccountJpaClient, BankAccountMapper mapper) {
        this.bankAccountJpaClient = bankAccountJpaClient;
        this.mapper = mapper;
    }

    @Override
    public List<CustomerBankAccount> findAllByIds(Collection<BankAccountId> bankAccountIds) {
        if (bankAccountIds.isEmpty()) {
            return List.of();
        }

        var entityIds = new LinkedHashSet<CustomerBankAccountEntity.CustomerBankAccountId>();
        for (BankAccountId bankAccountId : bankAccountIds) {
            entityIds.add(mapper.toEntityId(bankAccountId));
        }

        List<CustomerBankAccountEntity> existingAccounts = bankAccountJpaClient.findAllById(entityIds);

        var existingIds = new HashSet<CustomerBankAccountEntity.CustomerBankAccountId>(existingAccounts.size());
        for (CustomerBankAccountEntity account : existingAccounts) {
            existingIds.add(account.getId());
        }

        List<CustomerBankAccountEntity> missingAccounts = new ArrayList<>();
        for (CustomerBankAccountEntity.CustomerBankAccountId entityId : entityIds) {
            if (!existingIds.contains(entityId)) {
                missingAccounts.add(createNewAccountEntity(entityId));
            }
        }

        List<CustomerBankAccountEntity> accounts = new ArrayList<>(existingAccounts.size() + missingAccounts.size());
        accounts.addAll(existingAccounts);
        if (!missingAccounts.isEmpty()) {
            bankAccountJpaClient.saveAll(missingAccounts).forEach(accounts::add);
        }

        List<CustomerBankAccount> domainAccounts = new ArrayList<>(accounts.size());
        for (CustomerBankAccountEntity account : accounts) {
            domainAccounts.add(mapper.toDomain(account));
        }
        return domainAccounts;
    }

    @Override
    public List<CustomerBankAccount> findAllByCustomerIds(Collection<String> customerIds) {
        if (customerIds.isEmpty()) {
            return List.of();
        }

        var entities = bankAccountJpaClient.findByCustomerIdIn(customerIds);
        return entities.stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public void saveAll(Collection<CustomerBankAccount> bankAccounts) {
        List<CustomerBankAccountEntity> entities = new ArrayList<>(bankAccounts.size());
        for (CustomerBankAccount bankAccount : bankAccounts) {
            entities.add(mapper.toEntity(bankAccount));
        }
        bankAccountJpaClient.saveAll(entities);
    }

    private CustomerBankAccountEntity createNewAccountEntity(CustomerBankAccountEntity.CustomerBankAccountId entityId) {
        return CustomerBankAccountEntity.builder()
                .id(entityId)
                .balance(BigDecimal.valueOf(10000))
                .type(BankAccountType.CHECKING.toString())
                .build();
    }
}
