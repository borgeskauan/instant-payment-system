package br.kauan.paymentserviceprovider.port.output;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerJpaClient extends JpaRepository<CustomerEntity, String> {
}
