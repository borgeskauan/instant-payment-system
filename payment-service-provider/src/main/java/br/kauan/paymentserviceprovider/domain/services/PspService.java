package br.kauan.paymentserviceprovider.domain.services;

import br.kauan.paymentserviceprovider.domain.dto.TransferExecutionRequest;
import br.kauan.paymentserviceprovider.domain.dto.TransferPreviewRequest;
import br.kauan.paymentserviceprovider.domain.entity.TransferDetails;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferPreviewDetails;
import br.kauan.paymentserviceprovider.port.input.PspUseCase;
import org.springframework.stereotype.Service;

@Service
public class PspService implements PspUseCase {

    private final BankAccountCustomerService bankAccountCustomerService;

    private final CentralTransferService centralTransferService;

    public PspService(BankAccountCustomerService bankAccountCustomerService, CentralTransferService centralTransferService) {
        this.bankAccountCustomerService = bankAccountCustomerService;
        this.centralTransferService = centralTransferService;
    }

    @Override
    public TransferPreviewDetails fetchPaymentPreview(TransferPreviewRequest previewRequest) {
        var partyDetails = bankAccountCustomerService.findCustomerDetailsByPixKey(previewRequest.getReceiverPixKey())
                .orElseThrow(); // TODO: Improve exception handling

        return TransferPreviewDetails.builder()
                .receiver(partyDetails)
                .build();
    }

    @Override
    public TransferDetails requestTransfer(TransferExecutionRequest executionRequest) {
        var senderParty = bankAccountCustomerService.getInternalCustomerDetails(executionRequest.getSenderCustomerId())
                .orElseThrow(); // TODO: Improve exception handling

        // TODO: Create object to pass all needed data
        return centralTransferService.requestTransfer(senderParty, executionRequest.getReceiver(), executionRequest.getAmount());
    }
}
