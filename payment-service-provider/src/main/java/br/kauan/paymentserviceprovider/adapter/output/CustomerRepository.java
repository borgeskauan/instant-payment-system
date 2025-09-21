package br.kauan.paymentserviceprovider.adapter.output;

import br.kauan.paymentserviceprovider.domain.entity.Customer;
import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;

import java.util.Optional;

public interface CustomerRepository {
    Optional<Party> getCustomerDetails(String customerId);

    Customer save(Customer customer);

    Optional<Customer> findByTaxId(String taxId);

    Optional<Customer> findById(String customerId);
}
