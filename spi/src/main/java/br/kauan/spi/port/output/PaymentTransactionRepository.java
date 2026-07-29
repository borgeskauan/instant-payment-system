package br.kauan.spi.port.output;

import br.kauan.spi.domain.entity.security.AuthenticatedPaymentRequest;
import br.kauan.spi.domain.entity.security.AuthenticatedStatusReport;

import java.util.List;

public interface PaymentTransactionRepository {

    PaymentTransactionPersistenceResult storeAndClassifyIncomingPaymentRequests(
            List<AuthenticatedPaymentRequest> paymentTransactions
    );

    StatusReportPersistenceResult classifyAndApplyIncomingStatusReports(
            List<AuthenticatedStatusReport> statusReports
    );
}
