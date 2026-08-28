package br.kauan.paymentserviceprovider.domain.services;

import br.kauan.paymentserviceprovider.domain.dto.RawTransferExecutionRequest;
import br.kauan.paymentserviceprovider.domain.dto.TransferPreviewRequest;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferDetails;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferPreviewDetails;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferRequest;
import br.kauan.paymentserviceprovider.domain.services.cts.CentralTransferService;
import br.kauan.paymentserviceprovider.port.input.PspUseCase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class PspService implements PspUseCase {

    private final BankAccountPartyService bankAccountPartyService;
    private final CentralTransferService centralTransferService;

    public PspService(BankAccountPartyService bankAccountPartyService, CentralTransferService centralTransferService) {
        this.bankAccountPartyService = bankAccountPartyService;
        this.centralTransferService = centralTransferService;
    }

    @Override
    public TransferPreviewDetails fetchPaymentPreview(TransferPreviewRequest previewRequest) {
        log.info("[PIX FLOW - Step 1] Fetching payment preview for PIX key: {}", previewRequest.getReceiverPixKey());
        
        var partyDetails = bankAccountPartyService.findPartyDetailsByPixKey(previewRequest.getReceiverPixKey())
                .orElseThrow(); // TODO: Improve exception handling

        log.info("[PIX FLOW - Step 1] Payment preview fetched successfully. Receiver: {}", partyDetails.getName());
        return TransferPreviewDetails.builder()
                .receiver(partyDetails)
                .build();
    }

    @Override
    public TransferDetails requestTransfer(RawTransferExecutionRequest executionRequest) {
        log.info("[PIX FLOW - Step 2] PSP Pagador - Initiating transfer request. Customer: {}, Amount: {}", 
                executionRequest.getSenderCustomerId(), executionRequest.getAmount());
        
        var senderParty = bankAccountPartyService.getInternalPartyDetails(executionRequest.getSenderCustomerId())
                .orElseThrow(); // TODO: Improve exception handling

        log.debug("[PIX FLOW - Step 2] Sender details retrieved: {}", senderParty.getName());
        
        var transferRequest = TransferRequest.builder()
                .sender(senderParty)
                .receiver(executionRequest.getReceiver())
                .amount(executionRequest.getAmount())
                .description(executionRequest.getDescription())
                .build();

        log.info("[PIX FLOW - Step 2] Sending transfer request to Central Transfer Service");
        return centralTransferService.requestTransfer(transferRequest);
    }
}
