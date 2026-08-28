package br.kauan.paymentserviceprovider.domain.services.cts;

import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferDetails;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferRequest;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@Transactional
public class CentralTransferService {

    private final TransferRequestService transferRequestService;

    public CentralTransferService(
            TransferRequestService transferRequestService
    ) {
        this.transferRequestService = transferRequestService;
    }

    public TransferDetails requestTransfer(TransferRequest transferRequest) {
        return transferRequestService.requestTransfer(transferRequest);
    }
}