package br.kauan.paymentserviceprovider.adapter.output.customer;

import br.kauan.paymentserviceprovider.domain.entity.customer.Customer;

import java.util.Optional;

public interface CustomerRepository {
    Customer save(Customer customer);

    Optional<Customer> findByTaxId(String taxId);

    Optional<Customer> findById(String customerId);
}
