package br.kauan.paymentserviceprovider.adapter.output;

import br.kauan.paymentserviceprovider.config.GlobalVariables;
import br.kauan.paymentserviceprovider.domain.entity.Customer;
import br.kauan.paymentserviceprovider.domain.entity.CustomerBankAccount;
import br.kauan.paymentserviceprovider.domain.entity.transfer.BankAccount;
import br.kauan.paymentserviceprovider.domain.entity.transfer.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.transfer.BankAccountType;
import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;
import br.kauan.paymentserviceprovider.port.output.BankAccountRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class CustomerJpaAdapter implements CustomerRepository {

    private final CustomerJpaClient customerJpaClient;
    private final BankAccountRepository bankAccountRepository;

    public CustomerJpaAdapter(CustomerJpaClient customerJpaClient, BankAccountRepository bankAccountRepository) {
        this.customerJpaClient = customerJpaClient;
        this.bankAccountRepository = bankAccountRepository;
    }

    @Override
    public Optional<Party> getCustomerDetails(String customerId) {
        var customerDataOptional = customerJpaClient.findById(customerId);
        if (customerDataOptional.isEmpty()) {
            return Optional.empty();
        }

        var customerData = customerDataOptional.get();

        var bankAccount = createBankAccountFromCustomerEntity(customerData);

        return Optional.ofNullable(Party.builder()
                .name(customerData.getName())
                .taxId(customerData.getTaxId())
                .account(bankAccount)
                .build());
    }

    @Override
    public Customer save(Customer customer) {
        var accountId = customer.getBankAccount().getAccount().getId();

        var entity = CustomerEntity.builder()
                .id(customer.getId())
                .name(customer.getName())
                .taxId(customer.getTaxId())
                .accountNumber(accountId.getAccountNumber())
                .accountAgency(accountId.getAgencyNumber())
                .accountType(customer.getBankAccount().getAccount().getType().name())
                .build();

        var savedEntity = customerJpaClient.save(entity);

        return createCustomerFromEntity(savedEntity);
    }

    private Customer createCustomerFromEntity(CustomerEntity savedEntity) {
        var customerBankAccount = createCustomerBankAccount(savedEntity);

        return Customer.builder()
                .id(savedEntity.getId())
                .name(savedEntity.getName())
                .taxId(savedEntity.getTaxId())
                .bankAccount(customerBankAccount)
                .build();
    }

    @Override
    public Optional<Customer> findByTaxId(String taxId) {
        var customerDataOptional = customerJpaClient.findByTaxId(taxId);
        if (customerDataOptional.isEmpty()) {
            return Optional.empty();
        }

        var customerEntity = customerDataOptional.get();

        return Optional.ofNullable(createCustomerFromEntity(customerEntity));
    }

    private CustomerBankAccount createCustomerBankAccount(CustomerEntity customerData) {
        var bankAccount = createBankAccountFromCustomerEntity(customerData);

        var balance = bankAccountRepository.findById(bankAccount.getId()).orElseThrow().getBalance();

        return CustomerBankAccount.builder()
                .account(bankAccount)
                .balance(balance)
                .build();
    }

    private static BankAccount createBankAccountFromCustomerEntity(CustomerEntity customerData) {
        var bankAccountId = BankAccountId.builder()
                .bankCode(GlobalVariables.getBankCode())
                .accountNumber(customerData.getAccountNumber())
                .agencyNumber(customerData.getAccountAgency())
                .build();

        return BankAccount.builder()
                .id(bankAccountId)
                .type(BankAccountType.fromString(customerData.getAccountType()))
                .build();
    }
}
