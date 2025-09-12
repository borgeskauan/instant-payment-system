package br.kauan.paymentserviceprovider.port.output;

import br.kauan.paymentserviceprovider.domain.entity.BankAccount;
import br.kauan.paymentserviceprovider.domain.entity.Party;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class CustomerJpaAdapter implements CustomerRepository {

    @Value("${bank.code}")
    private static String BANK_CODE;

    private final CustomerJpaClient customerJpaClient;

    public CustomerJpaAdapter(CustomerJpaClient customerJpaClient) {
        this.customerJpaClient = customerJpaClient;
    }

    @Override
    public Optional<Party> getCustomerDetails(String customerId) {
        var customerDataOptional = customerJpaClient.findById(customerId);
        if (customerDataOptional.isEmpty()) {
            return Optional.empty();
        }

        var customerData = customerDataOptional.get();

        var bankAccount = BankAccount.builder()
                .bankCode(BANK_CODE)
                .accountNumber(customerData.getAccountNumber())
                .agencyNumber(customerData.getAccountNumber())
                .build();

        return Optional.ofNullable(Party.builder()
                .name(customerData.getName())
                .taxId(customerData.getTaxId())
                .bankAccount(bankAccount)
                .build());
    }
}
