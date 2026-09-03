package br.kauan.spi.port.output;

import br.kauan.spi.domain.entity.security.AuthenticatedStatusReport;
import br.kauan.spi.domain.entity.status.PaymentRejection;
import br.kauan.spi.domain.entity.status.PaymentSettlement;

import java.util.List;

public record StatusReportPersistenceResult(
        List<PaymentSettlement> settlements,
        List<PaymentRejection> rejectedPayments,
        List<AuthenticatedStatusReport> divergentStatusReports,
        List<AuthenticatedStatusReport> unauthorizedStatusReports
) {
}
