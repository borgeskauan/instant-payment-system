package br.kauan.spi.domain.services;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.StatusBatch;
import br.kauan.spi.domain.entity.status.StatusReport;
import br.kauan.spi.domain.entity.transfer.PaymentBatch;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.domain.services.notification.NotificationService;
import br.kauan.spi.domain.services.tracing.SpiTraceEvent;
import br.kauan.spi.domain.services.tracing.SpiTraceRecorder;
import br.kauan.spi.port.input.PaymentTransactionProcessorUseCase;
import br.kauan.spi.port.output.PaymentTransactionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class PaymentTransactionProcessorService implements PaymentTransactionProcessorUseCase {

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final NotificationService notificationService;
    private final SettlementService settlementService;
    private final SpiTraceRecorder traceRecorder;

    public PaymentTransactionProcessorService(
            PaymentTransactionRepository paymentTransactionRepository,
            NotificationService notificationService,
            SettlementService settlementService,
            SpiTraceRecorder traceRecorder
    ) {
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.notificationService = notificationService;
        this.settlementService = settlementService;
        this.traceRecorder = traceRecorder;
    }

    @Override
    public void processTransactionBatch(PaymentBatch transactionBatch) {
        List<PaymentTransaction> transactions = transactionBatch.getTransactions();
        if (transactions.isEmpty()) {
            return;
        }

        log.debug("[PIX FLOW - Step 3] SPI received transaction request batch. payments={}",
                transactions.size());
        paymentTransactionRepository.saveTransactions(transactions, PaymentStatus.WAITING_ACCEPTANCE);

        for (var paymentTransaction : transactions) {
            traceRecorder.record(paymentTransaction.getPaymentId(), SpiTraceEvent.REQUEST_SAVED);
        }
        notificationService.sendAcceptanceRequests(transactions);
        for (var paymentTransaction : transactions) {
            traceRecorder.record(paymentTransaction.getPaymentId(), SpiTraceEvent.ACCEPTANCE_NOTIFICATION_ENQUEUED);
        }
    }

    @Override
    public void processStatusBatch(StatusBatch statusBatch) {
        List<StatusReport> statusReports = statusBatch.getStatusReports();
        List<StatusReport> acceptedReports = new ArrayList<>(statusReports.size());
        for (StatusReport statusReport : statusReports) {
            if (statusReport.getStatus() == PaymentStatus.ACCEPTED_IN_PROCESS) {
                acceptedReports.add(statusReport);
            }
        }

        if (!acceptedReports.isEmpty()) {
            List<String> acceptedPaymentIds = new ArrayList<>(acceptedReports.size());
            for (StatusReport acceptedReport : acceptedReports) {
                acceptedPaymentIds.add(acceptedReport.getOriginalPaymentId());
            }
            processAcceptedPayments(acceptedPaymentIds);
        }

        List<StatusReport> rejectedReports = new ArrayList<>();
        for (var statusReport : statusReports) {
            if (statusReport.getStatus() == PaymentStatus.REJECTED) {
                rejectedReports.add(statusReport);
            }
        }
        if (!rejectedReports.isEmpty()) {
            processRejectedStatusReports(rejectedReports);
        }
    }

    private void processTransaction(PaymentTransaction paymentTransaction) {
        log.debug("[PIX FLOW - Step 3] SPI received transaction request. Payment ID: {}, Amount: {}", 
                paymentTransaction.getPaymentId(), paymentTransaction.getAmountCents());
        
        paymentTransactionRepository.saveTransaction(paymentTransaction, PaymentStatus.WAITING_ACCEPTANCE);
        traceRecorder.record(paymentTransaction.getPaymentId(), SpiTraceEvent.REQUEST_SAVED);
        log.debug("[PIX FLOW - Step 3] Transaction saved with status WAITING_ACCEPTANCE");

        notificationService.sendAcceptanceRequests(List.of(paymentTransaction));
        traceRecorder.record(paymentTransaction.getPaymentId(), SpiTraceEvent.ACCEPTANCE_NOTIFICATION_ENQUEUED);
    }

    private void processRejectedStatusReports(List<StatusReport> statusReports) {
        List<PaymentTransaction> rejectedPayments = new ArrayList<>(statusReports.size());

        for (StatusReport statusReport : statusReports) {
            log.debug("[PIX FLOW - Step 5] SPI received status report. Payment ID: {}, Status: {}",
                    statusReport.getOriginalPaymentId(), statusReport.getStatus());

            var paymentTransaction = paymentTransactionRepository.findById(statusReport.getOriginalPaymentId())
                    .orElseThrow(); // TODO: Use a better exception here
            log.warn("[PIX FLOW - Step 5] Payment rejected by PSP Recebedor");
            paymentTransactionRepository.updateStatus(paymentTransaction.getPaymentId(), PaymentStatus.REJECTED);
            rejectedPayments.add(paymentTransaction);
        }

        log.debug("[PIX FLOW - Rejection] Sending rejection notification batch to PSP Pagador. payments={}",
                rejectedPayments.size());
        notificationService.sendRejectionNotifications(rejectedPayments);
    }

    private void processAcceptedPayments(List<String> paymentIds) {
        log.debug("[PIX FLOW - Step 6] SPI initiating batch settlement via PI accounts at BCB. payments={}",
                paymentIds.size());

        List<PaymentTransaction> settledPayments = settlementService.tryMakeSettlements(paymentIds);
        Set<String> settledPaymentIds = new HashSet<>(settledPayments.size());
        for (PaymentTransaction settledPayment : settledPayments) {
            settledPaymentIds.add(settledPayment.getPaymentId());
        }

        for (PaymentTransaction paymentTransaction : settledPayments) {
            String paymentId = paymentTransaction.getPaymentId();
            traceRecorder.record(paymentId, SpiTraceEvent.SETTLEMENT_COMPLETED);
        }
        if (!settledPayments.isEmpty()) {
            notificationService.sendConfirmationNotifications(settledPayments);
            for (PaymentTransaction paymentTransaction : settledPayments) {
                String paymentId = paymentTransaction.getPaymentId();
                traceRecorder.record(paymentId, SpiTraceEvent.CONFIRMATION_NOTIFICATION_ENQUEUED);
            }
        }

        for (String paymentId : paymentIds) {
            if (!settledPaymentIds.contains(paymentId)) {
                paymentTransactionRepository.updateStatus(
                        paymentId,
                        PaymentStatus.ACCEPTED_IN_PROCESS
                );
            }
        }

        log.debug("[PIX FLOW - Complete] Batch settlement processed. requested={}, settled={}",
                paymentIds.size(), settledPayments.size());
    }

}
