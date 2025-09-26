package br.kauan.paymentserviceprovider.domain.services;

import br.kauan.paymentserviceprovider.adapter.output.CentralTransferSystemRestClient;
import br.kauan.paymentserviceprovider.adapter.output.pacs.PaymentTransactionMapper;
import br.kauan.paymentserviceprovider.config.GlobalVariables;
import br.kauan.paymentserviceprovider.domain.entity.TransferDetails;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentBatch;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferRequest;
import br.kauan.paymentserviceprovider.port.output.PaymentRepository;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@Transactional
public class TransferRequestService {
    
    private final PaymentTransactionFactory paymentTransactionFactory;
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionMapper paymentTransactionMapper;
    private final CentralTransferSystemRestClient transferRestClient;

    public TransferRequestService(
            PaymentTransactionFactory paymentTransactionFactory,
            PaymentRepository paymentRepository,
            PaymentTransactionMapper paymentTransactionMapper,
            CentralTransferSystemRestClient transferRestClient) {
        this.paymentTransactionFactory = paymentTransactionFactory;
        this.paymentRepository = paymentRepository;
        this.paymentTransactionMapper = paymentTransactionMapper;
        this.transferRestClient = transferRestClient;
    }

    public TransferDetails requestTransfer(TransferRequest transferRequest) {
        log.info("Requesting transfer for amount: {}", transferRequest.getAmount());

        PaymentBatch paymentBatch = paymentTransactionFactory.createPaymentBatch(transferRequest);
        PaymentTransaction transaction = paymentBatch.getTransactions().getFirst();

        paymentRepository.save(transaction);
        log.debug("Saved payment transaction with ID: {}", transaction.getPaymentId());

        var regulatoryBatch = paymentTransactionMapper.toRegulatoryRequest(paymentBatch);
        transferRestClient.requestTransfer(GlobalVariables.getBankCode(), regulatoryBatch);

        return TransferDetails.builder()
                .transferId(transaction.getPaymentId())
                .build();
    }
}