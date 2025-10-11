package br.kauan.spi.port.input;

import br.kauan.spi.domain.entity.status.StatusBatch;
import br.kauan.spi.domain.entity.transfer.PaymentBatch;
import reactor.core.publisher.Mono;

public interface PaymentTransactionProcessorUseCase {
    Mono<Void> processTransactionBatch(String ispb, PaymentBatch transaction);

    Mono<Void> processStatusBatch(String ispb, StatusBatch statusBatch);
}
