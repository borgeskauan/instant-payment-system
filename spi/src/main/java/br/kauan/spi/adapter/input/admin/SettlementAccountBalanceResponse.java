package br.kauan.spi.adapter.input.admin;

import java.math.BigDecimal;

public record SettlementAccountBalanceResponse(
        String ispb,
        BigDecimal balance
) {
}
