package br.kauan.spi.port.output;

import java.math.BigDecimal;

public interface FundsRepository {
    BigDecimal getAvailableFunds(String senderBankCode);

    void updateAvailableFunds(String senderBankCode, BigDecimal funds);
}
