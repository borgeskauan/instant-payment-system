package br.kauan.paymentserviceprovider.port.output;

import br.kauan.paymentserviceprovider.domain.entity.transfer.BankAccount;
import br.kauan.paymentserviceprovider.domain.entity.transfer.BankAccountType;
import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;
import org.springframework.stereotype.Repository;

import java.util.Optional;

import static br.kauan.paymentserviceprovider.config.GlobalVariables.BANK_CODE;

@Repository
public class CustomerJpaAdapter implements CustomerRepository {

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
                .type(BankAccountType.fromString(customerData.getAccountType()))
                .build();

        return Optional.ofNullable(Party.builder()
                .name(customerData.getName())
                .taxId(customerData.getTaxId())
                .account(bankAccount)
                .build());
    }
}
