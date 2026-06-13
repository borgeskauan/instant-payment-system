package br.kauan.spi.adapter.input.admin;

import java.math.BigDecimal;

public record ProvisionSettlementAccountRequest(
        BigDecimal balance,
        boolean resetIfExists
) {
}
