package br.kauan.paymentserviceprovider.adapter.output.customer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CustomerJpaClient extends JpaRepository<CustomerEntity, String> {
    Optional<CustomerEntity> findByTaxId(String taxId);
}
