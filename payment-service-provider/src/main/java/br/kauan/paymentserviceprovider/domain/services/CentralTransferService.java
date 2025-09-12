package br.kauan.paymentserviceprovider.domain.services;

import br.kauan.paymentserviceprovider.adapter.output.CentralTransferRestClient;
import br.kauan.paymentserviceprovider.adapter.output.pacs.PaymentTransactionMapper;
import br.kauan.paymentserviceprovider.domain.entity.commons.BatchDetails;
import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentBatch;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static br.kauan.paymentserviceprovider.config.GlobalVariables.BANK_CODE;

@Service
public class CentralTransferService {

    private final CentralTransferRestClient transferRestClient;
    private final PaymentTransactionMapper paymentTransactionMapper;

    public CentralTransferService(CentralTransferRestClient transferRestClient, PaymentTransactionMapper paymentTransactionMapper) {
        this.transferRestClient = transferRestClient;
        this.paymentTransactionMapper = paymentTransactionMapper;
    }

    public void requestTransfer(Party sender, Party receiver, BigDecimal amount) {
        var paymentBatch = buildPaymentBatch(sender, receiver, amount);

        var regulatoryBatch = paymentTransactionMapper.toRegulatoryRequest(paymentBatch);

        transferRestClient.requestTransfer(BANK_CODE, regulatoryBatch);
    }

    private PaymentBatch buildPaymentBatch(Party sender, Party receiver, BigDecimal amount) {
        var paymentTransaction = PaymentTransaction.builder()
                .paymentId(UUID.randomUUID().toString())
                .amount(amount)
                .currency("BRL")
                .sender(sender)
                .receiver(receiver)
                .build();

        var batchDetails = BatchDetails.of(1);

        return PaymentBatch.builder()
                .batchDetails(batchDetails)
                .transactions(List.of(paymentTransaction))
                .build();
    }
}
