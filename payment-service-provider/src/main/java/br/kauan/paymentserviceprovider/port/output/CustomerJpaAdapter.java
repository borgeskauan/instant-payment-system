package br.kauan.paymentserviceprovider.port.output;

import br.kauan.paymentserviceprovider.config.GlobalVariables;
import br.kauan.paymentserviceprovider.domain.entity.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.transfer.BankAccount;
import br.kauan.paymentserviceprovider.domain.entity.transfer.BankAccountType;
import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;
import org.springframework.stereotype.Repository;

import java.util.Optional;

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

        var bankAccountId = BankAccountId.builder()
                .bankCode(GlobalVariables.getBankCode())
                .accountNumber(customerData.getAccountNumber())
                .agencyNumber(customerData.getAccountAgency())
                .build();

        var bankAccount = BankAccount.builder()
                .id(bankAccountId)
                .type(BankAccountType.fromString(customerData.getAccountType()))
                .build();

        return Optional.ofNullable(Party.builder()
                .name(customerData.getName())
                .taxId(customerData.getTaxId())
                .account(bankAccount)
                .build());
    }
}
