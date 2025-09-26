package br.kauan.paymentserviceprovider.domain.services;

import br.kauan.paymentserviceprovider.domain.dto.HttpTransferExecutionRequest;
import br.kauan.paymentserviceprovider.domain.dto.TransferPreviewRequest;
import br.kauan.paymentserviceprovider.domain.entity.TransferDetails;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferPreviewDetails;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferRequest;
import br.kauan.paymentserviceprovider.port.input.PspUseCase;
import org.springframework.stereotype.Service;

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
        var partyDetails = bankAccountPartyService.findPartyDetailsByPixKey(previewRequest.getReceiverPixKey())
                .orElseThrow(); // TODO: Improve exception handling

        return TransferPreviewDetails.builder()
                .receiver(partyDetails)
                .build();
    }

    @Override
    public TransferDetails requestTransfer(HttpTransferExecutionRequest executionRequest) {
        var senderParty = bankAccountPartyService.getInternalPartyDetails(executionRequest.getSenderCustomerId())
                .orElseThrow(); // TODO: Improve exception handling

        var transferRequest = TransferRequest.builder()
                .sender(senderParty)
                .receiver(executionRequest.getReceiver())
                .amount(executionRequest.getAmount())
                .description(executionRequest.getDescription())
                .build();

        return centralTransferService.requestTransfer(transferRequest);
    }
}
