package br.kauan.paymentserviceprovider.adapter.input;

import br.kauan.paymentserviceprovider.config.PspProperties;
import br.kauan.paymentserviceprovider.domain.dto.RawTransferExecutionRequest;
import br.kauan.paymentserviceprovider.domain.dto.TransferPreviewRequest;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferDetails;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferPreviewDetails;
import br.kauan.paymentserviceprovider.domain.services.PspService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@CrossOrigin(origins = "http://localhost:4200")
public class PspController {

    private final PspService pspService;
    private final PspProperties properties;

    public PspController(PspService pspService, PspProperties properties) {
        this.pspService = pspService;
        this.properties = properties;
    }

    @GetMapping("/info")
    public Map<String, Object> getInfo() {
        return Map.of("bankCode", properties.bankCode());
    }

    @PostMapping("/transfer/preview")
    public TransferPreviewDetails processPayment(@RequestBody TransferPreviewRequest previewRequest) {
        log.info("=== [PIX FLOW START - Preview] Cliente Pagador requesting transfer preview for PIX key: {} ===", 
                previewRequest.getReceiverPixKey());
        return pspService.fetchPaymentPreview(previewRequest);
    }

    @PostMapping("/transfer/execute")
    public TransferDetails requestTransfer(@RequestBody RawTransferExecutionRequest executionRequest) {
        log.info("=== [PIX FLOW START - Execution] Cliente Pagador executing transfer. Amount: {}, Receiver: {} ===", 
                executionRequest.getAmount(), executionRequest.getReceiver().getName());
        var result = pspService.requestTransfer(executionRequest);
        log.info("=== [PIX FLOW] Transfer request initiated successfully. Transfer ID: {} ===", result.getTransferId());
        return result;
    }
}
