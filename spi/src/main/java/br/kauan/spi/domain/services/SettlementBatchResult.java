package br.kauan.spi.domain.services;

import br.kauan.spi.domain.entity.transfer.PaymentTransaction;

import java.util.List;

public record SettlementBatchResult(
        List<PaymentTransaction> settledPayments,
        List<String> notSettledPaymentIds
) {
}
