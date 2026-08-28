package br.kauan.paymentserviceprovider.port.input;

import br.kauan.paymentserviceprovider.domain.dto.RawTransferExecutionRequest;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferDetails;
import br.kauan.paymentserviceprovider.domain.entity.transfer.TransferPreviewDetails;
import br.kauan.paymentserviceprovider.domain.dto.TransferPreviewRequest;

public interface PspUseCase {
    TransferPreviewDetails fetchPaymentPreview(TransferPreviewRequest previewRequest);

    TransferDetails requestTransfer(RawTransferExecutionRequest executionRequest);
}
