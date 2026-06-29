package br.kauan.paymentserviceprovider.domain.entity.mappers;

import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferRequest;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class PaymentTransactionFactory {
    private static final String CURRENCY_BRL = "BRL";

    public PaymentTransaction createPaymentTransaction(TransferRequest request) {
        return PaymentTransaction.builder()
                .paymentId(UUID.randomUUID().toString())
                .amount(request.getAmount())
                .currency(CURRENCY_BRL)
                .sender(request.getSender())
                .receiver(request.getReceiver())
                .description(request.getDescription())
                .build();
    }
}
