package br.kauan.spi.port.output;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;

public interface FundsRepository {
    Mono<BigDecimal> createAccountIfNotExists(String bankCode, BigDecimal initialBalance);
    Mono<BigDecimal> getAvailableFunds(String bankCode);
    Mono<Void> deductFunds(String bankCode, BigDecimal amount);
    Mono<Void> addFunds(String bankCode, BigDecimal amount);
}