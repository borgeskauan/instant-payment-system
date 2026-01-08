package br.kauan.paymentserviceprovider.adapter.input;

import br.kauan.paymentserviceprovider.config.GlobalVariables;
import br.kauan.paymentserviceprovider.domain.dto.RawTransferExecutionRequest;
import br.kauan.paymentserviceprovider.domain.dto.TransferPreviewRequest;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferDetails;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferPreviewDetails;
import br.kauan.paymentserviceprovider.port.input.PspUseCase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
public class PspController {

    private final PspUseCase pspUseCase;

    public PspController(PspUseCase pspUseCase) {
        this.pspUseCase = pspUseCase;
    }

    @GetMapping("/info")
    public Map<String, Object> getInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("bankCode", GlobalVariables.getBankCode());
        return info;
    }

    @PostMapping("/transfer/preview")
    public TransferPreviewDetails processPayment(@RequestBody TransferPreviewRequest previewRequest) {
        log.info("=== [PIX FLOW START - Preview] Cliente Pagador requesting transfer preview for PIX key: {} ===", 
                previewRequest.getReceiverPixKey());
        return pspUseCase.fetchPaymentPreview(previewRequest);
    }

    @PostMapping("/transfer/execute")
    public TransferDetails requestTransfer(@RequestBody RawTransferExecutionRequest executionRequest) {
        log.info("=== [PIX FLOW START - Execution] Cliente Pagador executing transfer. Amount: {}, Receiver: {} ===", 
                executionRequest.getAmount(), executionRequest.getReceiver().getName());
        var result = pspUseCase.requestTransfer(executionRequest);
        log.info("=== [PIX FLOW] Transfer request initiated successfully. Transfer ID: {} ===", result.getTransferId());
        return result;
    }
}
