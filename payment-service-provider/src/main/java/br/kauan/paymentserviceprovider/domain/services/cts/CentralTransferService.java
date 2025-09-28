package br.kauan.paymentserviceprovider.domain.services.cts;

import br.kauan.paymentserviceprovider.adapter.output.listener.CentralTransferNotificationListener;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferDetails;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferRequest;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@Transactional
public class CentralTransferService {

    private final TransferRequestService transferRequestService;
    private final StatusProcessingService statusProcessingService;
    private final IncomingTransactionService incomingTransactionService;
    private final CentralTransferNotificationListener notificationListener;

    public CentralTransferService(
            TransferRequestService transferRequestService,
            StatusProcessingService statusProcessingService,
            IncomingTransactionService incomingTransactionService,
            CentralTransferNotificationListener notificationListener
    ) {
        this.transferRequestService = transferRequestService;
        this.statusProcessingService = statusProcessingService;
        this.incomingTransactionService = incomingTransactionService;
        this.notificationListener = notificationListener;
    }

    @PostConstruct
    public void init() {
        notificationListener.startListeningForNotifications(
                statusProcessingService::handleStatusBatch,
                incomingTransactionService::handleTransferRequestBatch
        );
    }

    public TransferDetails requestTransfer(TransferRequest transferRequest) {
        return transferRequestService.requestTransfer(transferRequest);
    }
}