package br.kauan.paymentserviceprovider.adapter.input;

import br.kauan.paymentserviceprovider.domain.dto.HttpTransferExecutionRequest;
import br.kauan.paymentserviceprovider.domain.dto.TransferPreviewRequest;
import br.kauan.paymentserviceprovider.domain.entity.TransferDetails;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferPreviewDetails;
import br.kauan.paymentserviceprovider.port.input.PspUseCase;
import org.springframework.web.bind.annotation.*;

@RestController
public class PspController {

    private final PspUseCase pspUseCase;

    public PspController(PspUseCase pspUseCase) {
        this.pspUseCase = pspUseCase;
    }

    @PostMapping("/transfer/preview")
    public TransferPreviewDetails processPayment(@RequestBody TransferPreviewRequest previewRequest) {
        return pspUseCase.fetchPaymentPreview(previewRequest);
    }

    @PostMapping("/transfer/execute")
    public TransferDetails requestTransfer(@RequestBody HttpTransferExecutionRequest executionRequest) {
        return pspUseCase.requestTransfer(executionRequest);
    }
}
