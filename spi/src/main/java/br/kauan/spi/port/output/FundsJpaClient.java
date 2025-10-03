package br.kauan.spi.port.output;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

public interface FundsJpaClient extends JpaRepository<FundsEntity, String> {

    @Modifying(clearAutomatically = true)
    @Query("UPDATE FundsEntity f SET f.balance = f.balance - :amount WHERE f.bankCode = :bankCode AND f.balance >= :amount")
    int deductFunds(@Param("bankCode") String bankCode, @Param("amount") BigDecimal amount);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE FundsEntity f SET f.balance = f.balance + :amount WHERE f.bankCode = :bankCode AND f.balance >= :amount")
    int addFunds(@Param("bankCode") String bankCode, @Param("amount") BigDecimal amount);
}
