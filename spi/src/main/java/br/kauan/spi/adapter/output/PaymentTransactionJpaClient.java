package br.kauan.spi.adapter.output;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentTransactionJpaClient extends JpaRepository<PaymentTransactionEntity, String> {

    @Modifying
    @Transactional
    @Query("UPDATE PaymentTransactionEntity p SET p.status = :status WHERE p.paymentId = :paymentId")
    int updateStatus(@Param("paymentId") String paymentId, @Param("status") String status);
}
