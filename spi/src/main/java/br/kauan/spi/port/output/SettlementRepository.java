package br.kauan.spi.port.output;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;

import java.util.List;
import java.util.Optional;

public interface SettlementRepository {

    Optional<PaymentTransaction> settleAcceptedPayment(String paymentId, PaymentStatus currentStatus, PaymentStatus settledStatus);

    List<PaymentTransaction> settleAcceptedPayments(List<String> paymentIds, PaymentStatus currentStatus, PaymentStatus settledStatus);
}
