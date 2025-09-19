package br.kauan.paymentserviceprovider.adapter.output;

import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import br.kauan.paymentserviceprovider.port.output.PaymentRepository;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class PaymentInMemoryAdapter implements PaymentRepository {

    private final Map<String, PaymentTransaction> payments = new ConcurrentHashMap<>();

    @Override
    public Optional<PaymentTransaction> findById(String originalPaymentId) {
        return Optional.ofNullable(payments.get(originalPaymentId));
    }

    @Override
    public void save(PaymentTransaction transaction) {
        payments.put(transaction.getPaymentId(), transaction);
    }
}
