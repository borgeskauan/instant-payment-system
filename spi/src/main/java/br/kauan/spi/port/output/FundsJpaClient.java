package br.kauan.spi.port.output;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FundsJpaClient extends JpaRepository<FundsEntity, String> {

    @Modifying(clearAutomatically = true)
    @Query("UPDATE FundsEntity f SET f.balanceCents = f.balanceCents - :amountCents WHERE f.bankCode = :bankCode AND f.balanceCents >= :amountCents")
    int deductFunds(@Param("bankCode") String bankCode, @Param("amountCents") long amountCents);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE FundsEntity f SET f.balanceCents = f.balanceCents + :amountCents WHERE f.bankCode = :bankCode")
    int addFunds(@Param("bankCode") String bankCode, @Param("amountCents") long amountCents);
}
