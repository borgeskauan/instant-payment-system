package br.kauan.spi.port.input;

import br.kauan.spi.domain.entity.security.AuthenticatedPaymentRequest;
import br.kauan.spi.domain.entity.security.AuthenticatedStatusReport;
import br.kauan.spi.port.output.PaymentTransactionPersistenceResult;

import java.util.List;

public interface PaymentTransactionProcessorUseCase {
    PaymentTransactionPersistenceResult processTransactions(List<AuthenticatedPaymentRequest> transactions);

    StatusReportProcessingResult processStatusReports(List<AuthenticatedStatusReport> statusReports);
}
