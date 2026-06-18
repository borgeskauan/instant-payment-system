package br.kauan.spi.port.output;

import br.kauan.spi.domain.entity.status.PaymentStatus;

public interface SettlementRepository {

    boolean settleAcceptedPayment(String paymentId, PaymentStatus currentStatus, PaymentStatus settledStatus);
}
