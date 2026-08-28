package br.kauan.paymentserviceprovider.port.output;

import br.kauan.paymentserviceprovider.domain.entity.status.PaymentStatus;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;

import java.util.Collection;
import java.util.List;

public interface PaymentRepository {
    List<PaymentTransaction> findAllByIds(Collection<String> paymentIds);

    void saveAll(Collection<PaymentTransaction> transactions);

    IncomingPaymentRequestClassification storeAndClassifyIncomingRequests(Collection<PaymentTransaction> transactions);

    boolean claimFinalStatusApplication(String paymentId, PaymentStatus status);

    void markFinalStatusApplied(String paymentId, PaymentStatus status);

    void releaseFinalStatusApplicationClaim(String paymentId, PaymentStatus status);
}
