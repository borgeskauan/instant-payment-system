package br.kauan.paymentserviceprovider.domain.services.cts;

import br.kauan.paymentserviceprovider.adapter.output.listener.CentralTransferSystemRestClient;
import br.kauan.paymentserviceprovider.adapter.output.pacs.mappers.PaymentTransactionMapper;
import br.kauan.paymentserviceprovider.config.GlobalVariables;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferDetails;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentBatch;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferRequest;
import br.kauan.paymentserviceprovider.domain.entity.mappers.PaymentTransactionFactory;
import br.kauan.paymentserviceprovider.port.output.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper;

    public TransferRequestService(
            PaymentTransactionFactory paymentTransactionFactory,
            PaymentRepository paymentRepository,
            PaymentTransactionMapper paymentTransactionMapper,
            CentralTransferSystemRestClient transferRestClient,
            ObjectMapper objectMapper) {
        this.paymentTransactionFactory = paymentTransactionFactory;
        this.paymentRepository = paymentRepository;
        this.paymentTransactionMapper = paymentTransactionMapper;
        this.transferRestClient = transferRestClient;
        this.objectMapper = objectMapper;
    }

    public TransferDetails requestTransfer(TransferRequest transferRequest) {
        log.info("[PIX FLOW - Step 3] PSP Pagador preparing transfer request. Amount: {}, Receiver: {}", 
                transferRequest.getAmount(), transferRequest.getReceiver().getName());

        PaymentBatch paymentBatch = paymentTransactionFactory.createPaymentBatch(transferRequest);
        PaymentTransaction transaction = paymentBatch.getTransactions().getFirst();

        paymentRepository.save(transaction);
        log.debug("[PIX FLOW - Step 3] Saved payment transaction with ID: {}", transaction.getPaymentId());

        try {
            var regulatoryBatch = paymentTransactionMapper.toRegulatoryRequest(paymentBatch);
            byte[] requestBytes = objectMapper.writeValueAsBytes(regulatoryBatch);
            log.info("[PIX FLOW - Step 3] Sending PACS.008 transfer request to kafka-producer for bank: {}, payload size: {} bytes", 
                    GlobalVariables.getBankCode(), requestBytes.length);
            transferRestClient.requestTransfer(GlobalVariables.getBankCode(), requestBytes);
            log.info("[PIX FLOW - Step 3] Transfer request sent successfully to kafka-producer (will be forwarded to SPI)");
        } catch (Exception e) {
            log.error("[PIX FLOW - Error] Failed to serialize or send transfer request", e);
            throw new RuntimeException("Failed to send transfer request", e);
        }

        return TransferDetails.builder()
                .transferId(transaction.getPaymentId())
                .build();
    }
}