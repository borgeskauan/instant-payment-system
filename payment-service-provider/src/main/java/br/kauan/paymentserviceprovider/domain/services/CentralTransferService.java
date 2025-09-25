package br.kauan.paymentserviceprovider.domain.services;

import br.kauan.paymentserviceprovider.adapter.output.CentralTransferNotificationListener;
import br.kauan.paymentserviceprovider.adapter.output.CentralTransferRestClient;
import br.kauan.paymentserviceprovider.adapter.output.pacs.PaymentTransactionMapper;
import br.kauan.paymentserviceprovider.adapter.output.pacs.StatusReportMapper;
import br.kauan.paymentserviceprovider.config.GlobalVariables;
import br.kauan.paymentserviceprovider.domain.entity.transfer.BankAccountId;
import br.kauan.paymentserviceprovider.domain.entity.TransferDetails;
import br.kauan.paymentserviceprovider.domain.entity.commons.BatchDetails;
import br.kauan.paymentserviceprovider.domain.entity.status.PaymentStatus;
import br.kauan.paymentserviceprovider.domain.entity.status.StatusBatch;
import br.kauan.paymentserviceprovider.domain.entity.status.StatusReport;
import br.kauan.paymentserviceprovider.domain.entity.transfer.BankAccount;
import br.kauan.paymentserviceprovider.domain.entity.transfer.Party;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentBatch;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentTransaction;
import br.kauan.paymentserviceprovider.port.output.PaymentRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@Transactional
public class CentralTransferService {

    private static final String CURRENCY_BRL = "BRL";

    private final CentralTransferRestClient transferRestClient;
    private final PaymentTransactionMapper paymentTransactionMapper;
    private final StatusReportMapper statusReportMapper;
    private final CentralTransferNotificationListener notificationListener;
    private final PaymentRepository paymentRepository;
    private final BankAccountCustomerService bankAccountCustomerService;

    public CentralTransferService(
            CentralTransferRestClient transferRestClient,
            PaymentTransactionMapper paymentTransactionMapper,
            StatusReportMapper statusReportMapper,
            CentralTransferNotificationListener notificationListener,
            PaymentRepository paymentRepository,
            BankAccountCustomerService bankAccountCustomerService
    ) {
        this.transferRestClient = transferRestClient;
        this.paymentTransactionMapper = paymentTransactionMapper;
        this.statusReportMapper = statusReportMapper;
        this.notificationListener = notificationListener;
        this.paymentRepository = paymentRepository;
        this.bankAccountCustomerService = bankAccountCustomerService;
    }

    @PostConstruct
    public void init() {
        notificationListener.startListeningForNotifications(this::handleStatusBatch, this::handleTransferRequestBatch);
    }

    public TransferDetails requestTransfer(Party sender, Party receiver, BigDecimal amount) {
        PaymentBatch paymentBatch = buildPaymentBatch(sender, receiver, amount);
        PaymentTransaction transaction = paymentBatch.getTransactions().getFirst();

        paymentRepository.save(transaction);

        var regulatoryBatch = paymentTransactionMapper.toRegulatoryRequest(paymentBatch);
        transferRestClient.requestTransfer(GlobalVariables.getBankCode(), regulatoryBatch);

        return TransferDetails.builder()
                .transferId(transaction.getPaymentId())
                .build();
    }

    private PaymentBatch buildPaymentBatch(Party sender, Party receiver, BigDecimal amount) {
        PaymentTransaction paymentTransaction = PaymentTransaction.builder()
                .paymentId(UUID.randomUUID().toString())
                .amount(amount)
                .currency(CURRENCY_BRL)
                .sender(sender)
                .receiver(receiver)
                .build();

        BatchDetails batchDetails = BatchDetails.of(1);

        return PaymentBatch.builder()
                .batchDetails(batchDetails)
                .transactions(List.of(paymentTransaction))
                .build();
    }

    private void handleStatusBatch(StatusBatch statusBatch) {
        log.info("Received status batch with {} reports", statusBatch.getStatusReports().size());

        statusBatch.getStatusReports().forEach(this::processStatusReport);
    }

    private void handleTransferRequestBatch(PaymentBatch paymentBatch) {
        log.info("Received payment batch with {} transactions", paymentBatch.getTransactions().size());

        paymentBatch.getTransactions().forEach(this::processIncomingTransaction);
    }

    private void processIncomingTransaction(PaymentTransaction transaction) {
        log.info("Processing incoming transaction: {}", transaction.getPaymentId());

        StatusReport statusReport = handleIncomingTransaction(transaction);
        StatusBatch statusBatch = buildStatusBatch(statusReport);

        var regulatoryStatusBatch = statusReportMapper.toRegulatoryReport(statusBatch);
        transferRestClient.sendTransferStatus(GlobalVariables.getBankCode(), regulatoryStatusBatch);
    }

    private StatusBatch buildStatusBatch(StatusReport statusReport) {
        return StatusBatch.builder()
                .batchDetails(BatchDetails.of(1))
                .statusReports(List.of(statusReport))
                .build();
    }

    private void processStatusReport(StatusReport statusReport) {
        log.info("Processing status report for payment: {}", statusReport.getOriginalPaymentId());

        if (PaymentStatus.REJECTED.equals(statusReport.getStatus())) {
            log.debug("Payment {} was rejected, no action required", statusReport.getOriginalPaymentId());
            return;
        }

        // TODO: Improve error handling with custom exception
        PaymentTransaction payment = paymentRepository.findById(statusReport.getOriginalPaymentId())
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + statusReport.getOriginalPaymentId()));

        handleSettlement(statusReport.getStatus(), payment);
    }

    private void handleSettlement(PaymentStatus status, PaymentTransaction payment) {
        if (PaymentStatus.ACCEPTED_AND_SETTLED_FOR_RECEIVER.equals(status)) {
            creditReceiverAccount(payment);
        } else if (PaymentStatus.ACCEPTED_AND_SETTLED_FOR_SENDER.equals(status)) {
            debitSenderAccount(payment);
        }
    }

    private void creditReceiverAccount(PaymentTransaction payment) {
        var bankAccountId = getBankAccountId(payment.getReceiver().getAccount());
        bankAccountCustomerService.addAmountToAccount(bankAccountId, payment.getAmount());
        log.info("Credited amount {} to receiver account {}", payment.getAmount(), bankAccountId);
    }

    private void debitSenderAccount(PaymentTransaction payment) {
        var bankAccountId = getBankAccountId(payment.getSender().getAccount());
        bankAccountCustomerService.removeAmountFromAccount(bankAccountId, payment.getAmount());
        log.info("Debited amount {} from sender account {}", payment.getAmount(), bankAccountId);
    }

    private BankAccountId getBankAccountId(BankAccount account) {
        return account.getId();
    }

    private StatusReport handleIncomingTransaction(PaymentTransaction transaction) {
        paymentRepository.save(transaction);
        log.info("Saved incoming transaction: {}", transaction.getPaymentId());

        // For demonstration, we assume all incoming transactions are approved
        return StatusReport.builder()
                .originalPaymentId(transaction.getPaymentId())
                .status(PaymentStatus.ACCEPTED_IN_PROCESS)
                .build();
    }
}