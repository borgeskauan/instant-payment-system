package br.kauan.paymentserviceprovider.domain.services;

import br.kauan.paymentserviceprovider.domain.entity.TransferPreviewDetails;
import br.kauan.paymentserviceprovider.domain.dto.TransferPreviewRequest;
import br.kauan.paymentserviceprovider.port.output.ExternalPartyRepository;
import br.kauan.paymentserviceprovider.port.input.PspUseCase;
import org.springframework.stereotype.Service;

@Service
public class PspService implements PspUseCase {

    private final ExternalPartyRepository externalPartyRepository;

    public PspService(ExternalPartyRepository externalPartyRepository) {
        this.externalPartyRepository = externalPartyRepository;
    }

    @Override
    public TransferPreviewDetails fetchPaymentPreview(TransferPreviewRequest previewRequest) {
        var partyDetails = externalPartyRepository.getPartyDetails(previewRequest.getReceiverPixKey());

        return TransferPreviewDetails.builder()
                .receiver(partyDetails)
                .build();
    }
}
