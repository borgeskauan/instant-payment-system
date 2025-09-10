package br.kauan.spi.port.input;

import br.kauan.spi.domain.entity.status.StatusBatch;
import br.kauan.spi.domain.entity.transfer.PaymentBatch;

public interface PaymentTransactionProcessorUseCase {
    void processTransactionBatch(String ispb, PaymentBatch transaction);

    void processStatusBatch(String ispb, StatusBatch statusBatch);
}
