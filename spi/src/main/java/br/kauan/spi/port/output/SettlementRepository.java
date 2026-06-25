package br.kauan.spi.port.output;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;

import java.util.List;

public interface SettlementRepository {

    /**
     * Settles waiting payments that can be debited and also returns payments that were already settled.
     * Returning already-settled payments makes status notification replay idempotent.
     */
    List<PaymentTransactionCommand> settleAcceptedPaymentsIdempotently(
            List<String> paymentIds,
            PaymentStatus currentStatus,
            PaymentStatus settledStatus
    );
}
