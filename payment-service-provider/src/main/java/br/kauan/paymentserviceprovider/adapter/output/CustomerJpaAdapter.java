package br.kauan.paymentserviceprovider.adapter.output;

import br.kauan.paymentserviceprovider.domain.entity.Customer;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class CustomerJpaAdapter implements CustomerRepository {

    private final CustomerJpaClient customerJpaClient;

    public CustomerJpaAdapter(CustomerJpaClient customerJpaClient) {
        this.customerJpaClient = customerJpaClient;
    }

    @Override
    public Customer save(Customer customer) {
        var entity = CustomerEntity.builder()
                .id(customer.getId())
                .name(customer.getName())
                .taxId(customer.getTaxId())
                .build();

        var savedEntity = customerJpaClient.save(entity);

        return createCustomerFromEntity(savedEntity);
    }

    @Override
    public Optional<Customer> findByTaxId(String taxId) {
        var customerDataOptional = customerJpaClient.findByTaxId(taxId);
        return mapCustomerOptionalToDomain(customerDataOptional);
    }

    @Override
    public Optional<Customer> findById(String customerId) {
        var customerDataOptional = customerJpaClient.findById(customerId);
        return mapCustomerOptionalToDomain(customerDataOptional);
    }

    private Customer createCustomerFromEntity(CustomerEntity savedEntity) {
        return Customer.builder()
                .id(savedEntity.getId())
                .name(savedEntity.getName())
                .taxId(savedEntity.getTaxId())
                .build();
    }

    private Optional<Customer> mapCustomerOptionalToDomain(Optional<CustomerEntity> customerDataOptional) {
        if (customerDataOptional.isEmpty()) {
            return Optional.empty();
        }

        var customerEntity = customerDataOptional.get();

        return Optional.ofNullable(createCustomerFromEntity(customerEntity));
    }
}
