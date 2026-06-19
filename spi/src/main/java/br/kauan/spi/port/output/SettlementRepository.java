package br.kauan.spi.port.output;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;

import java.util.Optional;

public interface SettlementRepository {

    Optional<PaymentTransaction> settleAcceptedPayment(String paymentId, PaymentStatus currentStatus, PaymentStatus settledStatus);
}
