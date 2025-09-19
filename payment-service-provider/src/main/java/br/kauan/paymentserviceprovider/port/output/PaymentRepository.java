package br.kauan.paymentserviceprovider.port.output;

import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;

import java.util.Optional;

public interface PaymentRepository {
    Optional<PaymentTransaction> findById(String originalPaymentId);

    void save(PaymentTransaction transaction);
}
