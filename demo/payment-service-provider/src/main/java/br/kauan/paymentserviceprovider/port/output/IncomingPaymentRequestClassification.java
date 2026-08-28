package br.kauan.paymentserviceprovider.port.output;

import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;

import java.util.List;

public record IncomingPaymentRequestClassification(
        List<PaymentTransaction> acceptedPaymentRequests,
        List<PaymentTransaction> divergentPaymentRequests
) {
    public IncomingPaymentRequestClassification {
        acceptedPaymentRequests = List.copyOf(acceptedPaymentRequests);
        divergentPaymentRequests = List.copyOf(divergentPaymentRequests);
    }
}
