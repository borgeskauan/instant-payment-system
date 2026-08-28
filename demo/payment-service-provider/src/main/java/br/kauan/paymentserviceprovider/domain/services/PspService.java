package br.kauan.paymentserviceprovider.domain.services;

import br.kauan.paymentserviceprovider.adapter.output.dict.DictClient;
import br.kauan.paymentserviceprovider.domain.dto.RawTransferExecutionRequest;
import br.kauan.paymentserviceprovider.domain.dto.TransferPreviewRequest;
import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferDetails;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferPreviewDetails;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferRequest;
import br.kauan.paymentserviceprovider.domain.services.cts.TransferRequestService;
import br.kauan.paymentserviceprovider.state.PspStateStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class PspService {

    private final DictClient dictClient;
    private final PspStateStore stateStore;
    private final TransferRequestService transferRequestService;

    public PspService(DictClient dictClient, PspStateStore stateStore, TransferRequestService transferRequestService) {
        this.dictClient = dictClient;
        this.stateStore = stateStore;
        this.transferRequestService = transferRequestService;
    }

    public TransferPreviewDetails fetchPaymentPreview(TransferPreviewRequest previewRequest) {
        log.info("[PIX FLOW - Step 1] Fetching payment preview for PIX key: {}", previewRequest.getReceiverPixKey());
        
        var partyDetails = dictClient.resolve(previewRequest.getReceiverPixKey());

        log.info("[PIX FLOW - Step 1] Payment preview fetched successfully. Receiver: {}", partyDetails.getName());
        return TransferPreviewDetails.builder()
                .receiver(partyDetails)
                .build();
    }

    public TransferDetails requestTransfer(RawTransferExecutionRequest executionRequest) {
        log.info("[PIX FLOW - Step 2] PSP Pagador - Initiating transfer request. Customer: {}, Amount: {}", 
                executionRequest.getSenderCustomerId(), executionRequest.getAmount());
        
        var customer = stateStore.findCustomerById(executionRequest.getSenderCustomerId())
                .orElseThrow(() -> new IllegalArgumentException("Sender customer not found"));
        var account = stateStore.findAccountByCustomerId(customer.getId())
                .orElseThrow(() -> new IllegalArgumentException("Sender account not found"));
        var senderParty = Party.builder()
                .name(customer.getName())
                .taxId(customer.getTaxId())
                .account(account.getAccount())
                .build();

        log.debug("[PIX FLOW - Step 2] Sender details retrieved: {}", senderParty.getName());
        
        var transferRequest = TransferRequest.builder()
                .sender(senderParty)
                .receiver(executionRequest.getReceiver())
                .amount(executionRequest.getAmount())
                .description(executionRequest.getDescription())
                .build();

        log.info("[PIX FLOW - Step 2] Sending transfer request to Central Transfer Service");
        return transferRequestService.requestTransfer(transferRequest);
    }
}
