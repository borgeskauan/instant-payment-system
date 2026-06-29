package br.kauan.paymentserviceprovider.port.output;

import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;

import java.util.Collection;
import java.util.List;

public interface PaymentRepository {
    List<PaymentTransaction> findAllByIds(Collection<String> paymentIds);

    void saveAll(Collection<PaymentTransaction> transactions);
}
