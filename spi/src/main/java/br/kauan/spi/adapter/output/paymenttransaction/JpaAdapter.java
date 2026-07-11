package br.kauan.spi.adapter.output.paymenttransaction;

import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.port.output.PaymentTransactionPersistenceResult;
import br.kauan.spi.port.output.PaymentTransactionRepository;
import br.kauan.spi.port.output.StatusReportPersistenceResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JpaAdapter implements PaymentTransactionRepository {

    private final IncomingPaymentRequestPersistence incomingPaymentRequestPersistence;
    private final IncomingStatusReportPersistence incomingStatusReportPersistence;

    public JpaAdapter(
            Mapper repositoryMapper,
            JdbcTemplate jdbcTemplate
    ) {
        this.incomingPaymentRequestPersistence = new IncomingPaymentRequestPersistence(jdbcTemplate);
        this.incomingStatusReportPersistence = new IncomingStatusReportPersistence(repositoryMapper, jdbcTemplate);
    }

    @Override
    public PaymentTransactionPersistenceResult storeAndClassifyIncomingPaymentRequests(
            List<PaymentTransactionCommand> paymentTransactions
    ) {
        return incomingPaymentRequestPersistence.storeAndClassify(paymentTransactions);
    }

    @Override
    public StatusReportPersistenceResult classifyAndApplyIncomingStatusReports(List<StatusReportCommand> statusReports) {
        return incomingStatusReportPersistence.classifyAndApply(statusReports);
    }
}
