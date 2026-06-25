package br.kauan.spi.port.input;

import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;

import java.util.List;

public interface PaymentTransactionProcessorUseCase {
    void processTransactions(List<PaymentTransactionCommand> transactions);

    void processStatusReports(List<StatusReportCommand> statusReports);
}
