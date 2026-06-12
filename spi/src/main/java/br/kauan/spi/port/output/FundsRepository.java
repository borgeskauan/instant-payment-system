package br.kauan.spi.port.output;

import java.math.BigDecimal;

public interface FundsRepository {
    BigDecimal ensureAccountExistsAndGetBalance(String bankCode, BigDecimal initialBalance);

    BigDecimal getAvailableFunds(String bankCode);

    void deductFunds(String bankCode, BigDecimal amount);

    void addFunds(String bankCode, BigDecimal amount);
}
