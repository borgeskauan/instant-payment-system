package br.kauan.spi.port.input;

import br.kauan.spi.domain.entity.status.StatusBatch;
import br.kauan.spi.domain.entity.transfer.PaymentBatch;

public interface PaymentTransactionProcessorUseCase {
    void processTransactionBatch(PaymentBatch transaction);

    void processStatusBatch(StatusBatch statusBatch);
}
