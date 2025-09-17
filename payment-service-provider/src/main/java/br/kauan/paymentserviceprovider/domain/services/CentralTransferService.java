package br.kauan.paymentserviceprovider.domain.services;

import br.kauan.paymentserviceprovider.adapter.output.CentralTransferNotificationListener;
import br.kauan.paymentserviceprovider.adapter.output.CentralTransferRestClient;
import br.kauan.paymentserviceprovider.adapter.output.pacs.PaymentTransactionMapper;
import br.kauan.paymentserviceprovider.adapter.output.pacs.StatusReportMapper;
import br.kauan.paymentserviceprovider.config.GlobalVariables;
import br.kauan.paymentserviceprovider.domain.entity.TransferDetails;
import br.kauan.paymentserviceprovider.domain.entity.commons.BatchDetails;
import br.kauan.paymentserviceprovider.domain.entity.status.PaymentStatus;
import br.kauan.paymentserviceprovider.domain.entity.status.StatusBatch;
import br.kauan.paymentserviceprovider.domain.entity.status.StatusReport;
import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentBatch;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class CentralTransferService {

    private final CentralTransferRestClient transferRestClient;

    private final PaymentTransactionMapper paymentTransactionMapper;
    private final StatusReportMapper statusReportMapper;

    private final CentralTransferNotificationListener notificationListener;

    public CentralTransferService(CentralTransferRestClient transferRestClient,
                                  PaymentTransactionMapper paymentTransactionMapper,
                                  StatusReportMapper statusReportMapper,
                                  CentralTransferNotificationListener notificationListener) {
        this.transferRestClient = transferRestClient;
        this.paymentTransactionMapper = paymentTransactionMapper;
        this.statusReportMapper = statusReportMapper;
        this.notificationListener = notificationListener;
    }

    @PostConstruct
    public void init() {
        notificationListener.startListeningForNotifications(this::handleStatusBatch, this::handleTransferRequestBatch);
    }

    public TransferDetails requestTransfer(Party sender, Party receiver, BigDecimal amount) {
        var paymentBatch = buildPaymentBatch(sender, receiver, amount);

        var regulatoryBatch = paymentTransactionMapper.toRegulatoryRequest(paymentBatch);

        transferRestClient.requestTransfer(GlobalVariables.getBankCode(), regulatoryBatch);

        return TransferDetails.builder()
                .transferId(paymentBatch.getTransactions().getFirst().getPaymentId())
                .build();
    }

    private PaymentBatch buildPaymentBatch(Party sender, Party receiver, BigDecimal amount) {
        var paymentTransaction = PaymentTransaction.builder()
                .paymentId(UUID.randomUUID().toString())
                .amount(amount)
                .currency("BRL")
                .sender(sender)
                .receiver(receiver)
                .build();

        var batchDetails = BatchDetails.of(1);

        return PaymentBatch.builder()
                .batchDetails(batchDetails)
                .transactions(List.of(paymentTransaction))
                .build();
    }

    private void handleStatusBatch(StatusBatch statusBatch) {
        log.info("Received status batch: {}", statusBatch);

        for (var statusReport : statusBatch.getStatusReports()) {
            log.info("Processing status report: {}", statusReport);

            handleStatusReport(statusReport);
        }
    }

    private void handleTransferRequestBatch(PaymentBatch paymentBatch) {
        log.info("Received payment batch: {}", paymentBatch);

        for (var transaction : paymentBatch.getTransactions()) {
            log.info("Processing transaction: {}", transaction);

            handleTransferRequest(transaction);
        }
    }

    private void handleTransferRequest(PaymentTransaction transaction) {
        var statusReport = handleIncomingTransaction(transaction);

        var statusBatch = StatusBatch.builder()
                .batchDetails(BatchDetails.of(1))
                .statusReports(List.of(statusReport))
                .build();

        var regulatoryStatusBatch = statusReportMapper.toRegulatoryReport(statusBatch);
        transferRestClient.sendTransferStatus(GlobalVariables.getBankCode(), regulatoryStatusBatch);
    }

    private void handleStatusReport(StatusReport statusReport) {
        if (PaymentStatus.REJECTED.equals(statusReport.getStatus())) {
            // Here you would implement the logic to handle rejected transactions
        }

        if (PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER.equals(statusReport.getStatus())) {
            // Here you would implement the logic to credit the receiver's account
        }

        if (PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER.equals(statusReport.getStatus())) {
            // Here you would implement the logic to debit the sender's account
        }
    }

    private StatusReport handleIncomingTransaction(PaymentTransaction transaction) {
        // For demonstration, we assume all incoming transactions are approved
        return StatusReport.builder()
                .originalPaymentId(transaction.getPaymentId())
                .status(PaymentStatus.ACCEPTED_IN_PROCESS)
                .build();
    }
}
