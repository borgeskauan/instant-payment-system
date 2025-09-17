package br.kauan.paymentserviceprovider.port.input;

import br.kauan.paymentserviceprovider.domain.dto.TransferExecutionRequest;
import br.kauan.paymentserviceprovider.domain.entity.TransferDetails;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferPreviewDetails;
import br.kauan.paymentserviceprovider.domain.dto.TransferPreviewRequest;

public interface PspUseCase {
    TransferPreviewDetails fetchPaymentPreview(TransferPreviewRequest previewRequest);

    TransferDetails requestTransfer(TransferExecutionRequest executionRequest);
}
