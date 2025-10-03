package br.kauan.spi.port.output;

import java.math.BigDecimal;

public interface FundsRepository {
    BigDecimal createAccountIfNotExists(String bankCode, BigDecimal amount);

    BigDecimal getAvailableFunds(String bankCode);

    void deductFunds(String bankCode, BigDecimal amount);

    void addFunds(String bankCode, BigDecimal amount);
}
