package br.kauan.paymentserviceprovider.domain.services.cts;

import br.kauan.paymentserviceprovider.adapter.output.CentralTransferSystemRestClient;
import br.kauan.paymentserviceprovider.adapter.output.pacs.StatusReportMapper;
import br.kauan.paymentserviceprovider.config.GlobalVariables;
import br.kauan.paymentserviceprovider.domain.entity.mappers.StatusReportFactory;
import br.kauan.paymentserviceprovider.domain.entity.status.PaymentStatus;
import br.kauan.paymentserviceprovider.domain.entity.status.StatusBatch;
import br.kauan.paymentserviceprovider.domain.entity.status.StatusReport;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentBatch;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import br.kauan.paymentserviceprovider.port.output.PaymentRepository;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@Transactional
public class IncomingTransactionService {
    
    private final PaymentRepository paymentRepository;
    private final StatusReportFactory statusReportFactory;
    private final StatusReportMapper statusReportMapper;
    private final CentralTransferSystemRestClient transferRestClient;

    public IncomingTransactionService(
            PaymentRepository paymentRepository,
            StatusReportFactory statusReportFactory,
            StatusReportMapper statusReportMapper,
            CentralTransferSystemRestClient transferRestClient) {
        this.paymentRepository = paymentRepository;
        this.statusReportFactory = statusReportFactory;
        this.statusReportMapper = statusReportMapper;
        this.transferRestClient = transferRestClient;
    }

    public void handleTransferRequestBatch(PaymentBatch paymentBatch) {
        log.info("Received payment batch with {} transactions", paymentBatch.getTransactions().size());
        paymentBatch.getTransactions().forEach(this::processIncomingTransaction);
    }

    private void processIncomingTransaction(PaymentTransaction transaction) {
        log.info("Processing incoming transaction: {}", transaction.getPaymentId());

        StatusReport statusReport = handleIncomingTransaction(transaction);
        StatusBatch statusBatch = statusReportFactory.createStatusBatch(statusReport);

        var regulatoryStatusBatch = statusReportMapper.toRegulatoryReport(statusBatch);
        transferRestClient.sendTransferStatus(GlobalVariables.getBankCode(), regulatoryStatusBatch);

        log.debug("Sent status report for transaction: {}", transaction.getPaymentId());
    }

    private StatusReport handleIncomingTransaction(PaymentTransaction transaction) {
        paymentRepository.save(transaction);
        log.info("Saved incoming transaction: {}", transaction.getPaymentId());

        // TODO: Implement proper business logic for transaction approval
        return buildApprovedStatusReport(transaction.getPaymentId());
    }

    private StatusReport buildApprovedStatusReport(String paymentId) {
        return StatusReport.builder()
                .originalPaymentId(paymentId)
                .status(PaymentStatus.ACCEPTED_IN_PROCESS)
                .build();
    }
}