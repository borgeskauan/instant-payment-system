package br.kauan.spi.port.output;

import br.kauan.spi.domain.entity.security.AuthenticatedStatusReport;
import br.kauan.spi.domain.entity.status.PaymentRejection;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;

import java.util.List;

public record StatusReportPersistenceResult(
        List<PaymentTransactionCommand> settledPayments,
        List<PaymentRejection> rejectedPayments,
        List<PaymentStatusTransition> appliedStatusTransitions,
        List<AuthenticatedStatusReport> divergentStatusReports,
        List<AuthenticatedStatusReport> unauthorizedStatusReports
) {
}
