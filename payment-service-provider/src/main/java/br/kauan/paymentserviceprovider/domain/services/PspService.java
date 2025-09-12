package br.kauan.paymentserviceprovider.domain.services;

import br.kauan.paymentserviceprovider.domain.dto.TransferExecutionRequest;
import br.kauan.paymentserviceprovider.domain.entity.TransferPreviewDetails;
import br.kauan.paymentserviceprovider.domain.dto.TransferPreviewRequest;
import br.kauan.paymentserviceprovider.port.output.CustomerRepository;
import br.kauan.paymentserviceprovider.port.output.ExternalPartyRepository;
import br.kauan.paymentserviceprovider.port.input.PspUseCase;
import org.springframework.stereotype.Service;

@Service
public class PspService implements PspUseCase {

    private final ExternalPartyRepository externalPartyRepository;
    private final CustomerRepository customerRepository;

    private final CentralTransferService centralTransferService;

    public PspService(ExternalPartyRepository externalPartyRepository, CustomerRepository customerRepository, CentralTransferService centralTransferService) {
        this.externalPartyRepository = externalPartyRepository;
        this.customerRepository = customerRepository;
        this.centralTransferService = centralTransferService;
    }

    @Override
    public TransferPreviewDetails fetchPaymentPreview(TransferPreviewRequest previewRequest) {
        var partyDetails = externalPartyRepository.getPartyDetails(previewRequest.getReceiverPixKey());

        return TransferPreviewDetails.builder()
                .receiver(partyDetails)
                .build();
    }

    @Override
    public void executePayment(TransferExecutionRequest executionRequest) {
        var senderParty = customerRepository.getCustomerDetails(executionRequest.getSenderCustomerId())
                .orElseThrow(); // TODO: Improve exception handling

        centralTransferService.requestTransfer(senderParty, executionRequest.getReceiver(), executionRequest.getAmount());
    }
}
