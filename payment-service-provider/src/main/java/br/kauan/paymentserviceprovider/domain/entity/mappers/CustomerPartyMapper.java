package br.kauan.paymentserviceprovider.domain.entity.mappers;

import br.kauan.paymentserviceprovider.domain.entity.customer.Customer;
import br.kauan.paymentserviceprovider.domain.entity.customer.CustomerBankAccount;
import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;
import org.springframework.stereotype.Component;

@Component
public class CustomerPartyMapper {

    public Party toParty(Customer customer, CustomerBankAccount bankAccount) {
        if (customer == null) {
            return null;
        }

        return Party.builder()
                .name(customer.getName())
                .taxId(customer.getTaxId())
                .account(bankAccount.getAccount())
                .build();
    }
}
