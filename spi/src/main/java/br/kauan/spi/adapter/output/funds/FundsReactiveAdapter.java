package br.kauan.spi.adapter.output.funds;

import br.kauan.spi.port.output.FundsReactiveClient;
import br.kauan.spi.port.output.FundsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

@Slf4j
@Repository
@RequiredArgsConstructor
public class FundsReactiveAdapter implements FundsRepository {

    private final R2dbcEntityTemplate r2dbcEntityTemplate;
    private final FundsReactiveClient fundsReactiveClient;

    @Override
    public Mono<BigDecimal> createAccountIfNotExists(String bankCode, BigDecimal initialBalance) {
        return fundsReactiveClient.findById(bankCode)
                .switchIfEmpty(
                        Mono.defer(() -> {
                                    var newEntity = FundsEntity.builder()
                                            .bankCode(bankCode)
                                            .balance(initialBalance)
                                            .build();
                                    return r2dbcEntityTemplate.insert(newEntity);
                                })
                                .onErrorResume(DuplicateKeyException.class, e -> {
                                    // If duplicate, just fetch the existing one
                                    return fundsReactiveClient.findById(bankCode);
                                })
                )
                .map(FundsEntity::getBalance)
                .onErrorResume(e -> {
                    log.warn("An error occurred when creating funds entity for {}", bankCode, e);
                    return Mono.error(e);
                });
    }

    @Override
    public Mono<BigDecimal> getAvailableFunds(String bankCode) {
        return createAccountIfNotExists(bankCode, BigDecimal.ZERO);
    }

    @Override
    public Mono<Void> deductFunds(String bankCode, BigDecimal amount) {
        return fundsReactiveClient.deductFunds(bankCode, amount)
                .flatMap(updatedRows -> {
                    if (updatedRows == 0) {
                        return Mono.error(new RuntimeException("Insufficient funds or account not found for bankCode: " + bankCode));
                    }
                    log.debug("Successfully deducted {} from account {}", amount, bankCode);
                    return Mono.empty();
                })
                .onErrorResume(e -> {
                    log.error("Error deducting funds for bankCode: {}", bankCode, e);
                    return Mono.error(e);
                }).then();
    }

    @Override
    public Mono<Void> addFunds(String bankCode, BigDecimal amount) {
        return fundsReactiveClient.addFunds(bankCode, amount)
                .flatMap(updatedRows -> {
                    if (updatedRows == 0) {
                        var fundsEntity = FundsEntity.builder()
                                .bankCode(bankCode)
                                .balance(amount)
                                .build();

                        // If no rows were updated, create the account with the initial amount
                        return r2dbcEntityTemplate.insert(fundsEntity)
                                .then(Mono.empty());
                    }
                    log.debug("Successfully added {} to account {}", amount, bankCode);
                    return Mono.empty();
                })
                .onErrorResume(e -> {
                    log.error("Error adding funds for bankCode: {}", bankCode, e);
                    return Mono.error(e);
                }).then();
    }
}