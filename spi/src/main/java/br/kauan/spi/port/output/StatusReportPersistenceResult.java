package br.kauan.spi.port.output;

import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;

import java.util.List;

public record StatusReportPersistenceResult(
        List<PaymentTransactionCommand> settledPayments,
        List<PaymentTransactionCommand> rejectedPayments,
        List<StatusReportCommand> divergentStatusReports
) {
}
