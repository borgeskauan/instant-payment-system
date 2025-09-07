package br.kauan.spi.port.input;

import br.kauan.spi.domain.entity.transfer.PaymentBatch;

public interface PaymentTransactionUseCase {
    void processTransaction(String ispb, PaymentBatch transaction);
}
