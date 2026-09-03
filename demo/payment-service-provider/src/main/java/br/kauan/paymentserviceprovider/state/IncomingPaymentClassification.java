package br.kauan.paymentserviceprovider.state;

import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;

import java.util.List;

public record IncomingPaymentClassification(
        List<PaymentTransaction> acceptedPayments,
        List<PaymentTransaction> divergentPayments
) {
    public IncomingPaymentClassification {
        acceptedPayments = List.copyOf(acceptedPayments);
        divergentPayments = List.copyOf(divergentPayments);
    }
}
