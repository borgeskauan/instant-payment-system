package br.kauan.paymentserviceprovider.domain.services.cts;

import br.kauan.paymentserviceprovider.adapter.output.listener.CentralTransferSystemRestClient;
import br.kauan.paymentserviceprovider.adapter.output.pacs.mappers.PaymentTransactionMapper;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferDetails;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferRequest;
import br.kauan.paymentserviceprovider.state.PaymentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class TransferRequestService {

    private static final String CURRENCY_BRL = "BRL";

    private final PaymentStore paymentStore;
    private final PaymentTransactionMapper paymentTransactionMapper;
    private final CentralTransferSystemRestClient transferRestClient;
    private final ObjectMapper objectMapper;

    public TransferRequestService(
            PaymentStore paymentStore,
            PaymentTransactionMapper paymentTransactionMapper,
            CentralTransferSystemRestClient transferRestClient,
            ObjectMapper objectMapper) {
        this.paymentStore = paymentStore;
        this.paymentTransactionMapper = paymentTransactionMapper;
        this.transferRestClient = transferRestClient;
        this.objectMapper = objectMapper;
    }

    public TransferDetails requestTransfer(TransferRequest transferRequest) {
        log.info("[PIX FLOW - Step 3] PSP Pagador preparing transfer request. Amount: {}, Receiver: {}", 
                transferRequest.getAmount(), transferRequest.getReceiver().getName());

        PaymentTransaction transaction = PaymentTransaction.builder()
                .paymentId(UUID.randomUUID().toString())
                .amount(transferRequest.getAmount())
                .currency(CURRENCY_BRL)
                .sender(transferRequest.getSender())
                .receiver(transferRequest.getReceiver())
                .description(transferRequest.getDescription())
                .build();

        paymentStore.saveAll(List.of(transaction));
        log.debug("[PIX FLOW - Step 3] Saved payment transaction with ID: {}", transaction.getPaymentId());

        try {
            var regulatoryRequest = paymentTransactionMapper.toRegulatoryRequest(transaction);
            byte[] requestBytes = objectMapper.writeValueAsBytes(regulatoryRequest);
            log.info("[PIX FLOW - Step 3] Sending PACS.008 transfer request to kafka-producer; payload size: {} bytes",
                    requestBytes.length);
            transferRestClient.requestTransfer(requestBytes);
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
