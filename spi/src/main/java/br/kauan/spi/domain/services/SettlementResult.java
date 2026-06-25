package br.kauan.spi.domain.services;

import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;

import java.util.List;

public record SettlementResult(
        List<PaymentTransactionCommand> settledOrAlreadySettledPayments,
        List<String> notSettledPaymentIds
) {
}
