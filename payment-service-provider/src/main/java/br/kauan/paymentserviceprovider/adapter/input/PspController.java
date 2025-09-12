package br.kauan.paymentserviceprovider.adapter.input;

import br.kauan.paymentserviceprovider.domain.dto.TransferExecutionRequest;
import br.kauan.paymentserviceprovider.domain.entity.TransferPreviewDetails;
import br.kauan.paymentserviceprovider.domain.dto.TransferPreviewRequest;
import br.kauan.paymentserviceprovider.port.input.PspUseCase;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

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
    public void executePayment(@RequestBody TransferExecutionRequest executionRequest) {
        // Implementation for executing the payment would go here
    }
}
