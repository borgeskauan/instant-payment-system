package br.kauan.spi.port.input;

import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.port.output.PaymentTransactionPersistenceResult;

import java.util.List;

public interface PaymentTransactionProcessorUseCase {
    PaymentTransactionPersistenceResult processTransactions(List<PaymentTransactionCommand> transactions);

    StatusReportProcessingResult processStatusReports(List<StatusReportCommand> statusReports);
}
