package br.kauan.paymentserviceprovider.adapter.input;

import br.kauan.paymentserviceprovider.domain.dto.TransferExecutionRequest;
import br.kauan.paymentserviceprovider.domain.dto.TransferPreviewRequest;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferDetails;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferPreviewDetails;
import br.kauan.paymentserviceprovider.domain.services.cts.OutgoingTransferService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@CrossOrigin(origins = "http://localhost:4200")
public class PspController {

    private final OutgoingTransferService outgoingTransferService;

    public PspController(OutgoingTransferService outgoingTransferService) {
        this.outgoingTransferService = outgoingTransferService;
    }

    @PostMapping("/transfer/preview")
    public TransferPreviewDetails processPayment(@RequestBody TransferPreviewRequest previewRequest) {
        log.info("=== [PIX FLOW START - Preview] Cliente Pagador requesting transfer preview for PIX key: {} ===", 
                previewRequest.receiverPixKey());
        return outgoingTransferService.preview(previewRequest);
    }

    @PostMapping("/transfer/execute")
    public TransferDetails requestTransfer(@RequestBody TransferExecutionRequest executionRequest) {
        log.info("=== [PIX FLOW START - Execution] Cliente Pagador executing transfer. Amount: {}, Receiver: {} ===", 
                executionRequest.amount(), executionRequest.receiverPixKey());
        var result = outgoingTransferService.execute(executionRequest);
        log.info("=== [PIX FLOW] Transfer request initiated successfully. Transfer ID: {} ===", result.getTransferId());
        return result;
    }
}
