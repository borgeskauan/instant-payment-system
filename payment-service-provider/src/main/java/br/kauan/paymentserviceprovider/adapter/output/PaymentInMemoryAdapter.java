package br.kauan.paymentserviceprovider.adapter.output;

import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import br.kauan.paymentserviceprovider.port.output.PaymentRepository;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class PaymentInMemoryAdapter implements PaymentRepository {

    private final Map<String, PaymentTransaction> payments = new ConcurrentHashMap<>();

    @Override
    public List<PaymentTransaction> findAllByIds(Collection<String> paymentIds) {
        List<PaymentTransaction> foundPayments = new ArrayList<>(paymentIds.size());
        for (String paymentId : paymentIds) {
            PaymentTransaction payment = payments.get(paymentId);
            if (payment != null) {
                foundPayments.add(payment);
            }
        }
        return foundPayments;
    }

    @Override
    public void saveAll(Collection<PaymentTransaction> transactions) {
        for (PaymentTransaction transaction : transactions) {
            payments.put(transaction.getPaymentId(), transaction);
        }
    }
}
