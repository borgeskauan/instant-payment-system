package br.kauan.spi.port.output;

import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;

import java.util.List;

public interface PaymentTransactionRepository {

    PaymentTransactionPersistenceResult storeAndClassifyIncomingPaymentRequests(
            List<PaymentTransactionCommand> paymentTransactions
    );

    StatusReportPersistenceResult classifyAndApplyIncomingStatusReports(List<StatusReportCommand> statusReports);

    void markAcceptedInProcessIfWaitingAcceptance(List<String> paymentIds);
}
