package br.kauan.paymentserviceprovider.domain.services;

import br.kauan.paymentserviceprovider.domain.entity.BatchDetails;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentBatch;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferRequest;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.UUID;

@Component
public class PaymentTransactionFactory {
    private static final String CURRENCY_BRL = "BRL";

    public PaymentBatch createPaymentBatch(TransferRequest request) {
        PaymentTransaction transaction = createPaymentTransaction(request);
        BatchDetails batchDetails = BatchDetails.of(1);

        return PaymentBatch.builder()
                .batchDetails(batchDetails)
                .transactions(Collections.singletonList(transaction))
                .build();
    }

    public PaymentTransaction createPaymentTransaction(TransferRequest request) {
        return PaymentTransaction.builder()
                .paymentId(generatePaymentId())
                .amount(request.getAmount())
                .currency(CURRENCY_BRL)
                .sender(request.getSender())
                .receiver(request.getReceiver())
                .description(request.getDescription())
                .build();
    }

    private String generatePaymentId() {
        return UUID.randomUUID().toString();
    }
}