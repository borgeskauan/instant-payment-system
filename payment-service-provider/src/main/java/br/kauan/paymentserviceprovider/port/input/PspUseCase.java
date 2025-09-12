package br.kauan.paymentserviceprovider.port.input;

import br.kauan.paymentserviceprovider.domain.entity.TransferPreviewDetails;
import br.kauan.paymentserviceprovider.domain.dto.TransferPreviewRequest;

public interface PspUseCase {
    TransferPreviewDetails fetchPaymentPreview(TransferPreviewRequest previewRequest);
}
