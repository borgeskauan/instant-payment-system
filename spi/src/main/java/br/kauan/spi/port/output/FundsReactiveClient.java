package br.kauan.spi.port.output;

import br.kauan.spi.adapter.output.funds.FundsEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

public interface FundsReactiveClient extends ReactiveCrudRepository<FundsEntity, String> {

    @Modifying
    @Query("UPDATE funds_entity SET balance = balance - :amount WHERE bank_code = :bankCode AND balance >= :amount")
    Mono<Integer> deductFunds(@Param("bankCode") String bankCode, @Param("amount") BigDecimal amount);

    @Modifying
    @Query("UPDATE funds_entity SET balance = balance + :amount WHERE bank_code = :bankCode")
    Mono<Integer> addFunds(@Param("bankCode") String bankCode, @Param("amount") BigDecimal amount);
}
