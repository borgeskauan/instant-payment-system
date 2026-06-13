package br.kauan.spi.port.output;

import java.math.BigDecimal;

public interface FundsRepository {
    void provisionAccount(String bankCode, BigDecimal balance, boolean resetIfExists);

    BigDecimal getAvailableFunds(String bankCode);

    void deductFunds(String bankCode, BigDecimal amount);

    void addFunds(String bankCode, BigDecimal amount);
}
