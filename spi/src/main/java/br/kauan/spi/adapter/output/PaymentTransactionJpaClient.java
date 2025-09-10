package br.kauan.spi.adapter.output;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentTransactionJpaClient extends JpaRepository<PaymentTransactionEntity, String> {
}
