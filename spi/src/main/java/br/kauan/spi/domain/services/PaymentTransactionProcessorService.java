package br.kauan.spi.domain.services;

import br.kauan.spi.Utils;
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

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
    public void processTransactionBatch(String ispb, PaymentBatch transactionBatch) {
        for (var payment : transactionBatch.getTransactions()) {
            processTransaction(payment);
        }
    }

    @Override
    public void processStatusBatch(String ispb, StatusBatch statusBatch) {
        List<StatusReport> acceptedReports = statusBatch.getStatusReports().stream()
                .filter(statusReport -> statusReport.getStatus() == PaymentStatus.ACCEPTED_IN_PROCESS)
                .toList();

        if (!acceptedReports.isEmpty()) {
            processAcceptedPayments(acceptedReports.stream()
                    .map(StatusReport::getOriginalPaymentId)
                    .toList());
        }

        for (var statusReport : statusBatch.getStatusReports()) {
            if (statusReport.getStatus() == PaymentStatus.REJECTED) {
                processRejectedStatusReport(statusReport);
            }
        }
    }

    private void processTransaction(PaymentTransaction paymentTransaction) {
        log.debug("[PIX FLOW - Step 3] SPI received transaction request. Payment ID: {}, Amount: {}", 
                paymentTransaction.getPaymentId(), paymentTransaction.getAmount());
        
        paymentTransactionRepository.saveTransaction(paymentTransaction, PaymentStatus.WAITING_ACCEPTANCE);
        traceRecorder.record(paymentTransaction.getPaymentId(), SpiTraceEvent.REQUEST_SAVED);
        log.debug("[PIX FLOW - Step 3] Transaction saved with status WAITING_ACCEPTANCE");

        String receiverBank = Utils.getBankCode(paymentTransaction.getReceiver());
        log.debug("[PIX FLOW - Step 4] SPI forwarding acceptance request to PSP Recebedor (Bank: {})", receiverBank);
        notificationService.sendAcceptanceRequest(receiverBank, paymentTransaction);
        traceRecorder.record(paymentTransaction.getPaymentId(), SpiTraceEvent.ACCEPTANCE_NOTIFICATION_ENQUEUED);
    }

    private void processRejectedStatusReport(StatusReport statusReport) {
        log.debug("[PIX FLOW - Step 5] SPI received status report. Payment ID: {}, Status: {}", 
                statusReport.getOriginalPaymentId(), statusReport.getStatus());

        var paymentTransaction = paymentTransactionRepository.findById(statusReport.getOriginalPaymentId())
                .orElseThrow(); // TODO: Use a better exception here
        log.warn("[PIX FLOW - Step 5] Payment rejected by PSP Recebedor");
        processRejectedPayment(paymentTransaction);
    }

    private void processAcceptedPayments(List<String> paymentIds) {
        log.debug("[PIX FLOW - Step 6] SPI initiating batch settlement via PI accounts at BCB. payments={}",
                paymentIds.size());

        List<PaymentTransaction> settledPayments = settlementService.tryMakeSettlements(paymentIds);
        Set<String> settledPaymentIds = settledPayments.stream()
                .map(PaymentTransaction::getPaymentId)
                .collect(Collectors.toSet());

        for (PaymentTransaction paymentTransaction : settledPayments) {
            String paymentId = paymentTransaction.getPaymentId();
            traceRecorder.record(paymentId, SpiTraceEvent.SETTLEMENT_COMPLETED);
            notificationService.sendConfirmationNotification(paymentTransaction);
            traceRecorder.record(paymentId, SpiTraceEvent.CONFIRMATION_NOTIFICATION_ENQUEUED);
        }

        paymentIds.stream()
                .filter(paymentId -> !settledPaymentIds.contains(paymentId))
                .forEach(paymentId -> paymentTransactionRepository.updateStatus(
                        paymentId,
                        PaymentStatus.ACCEPTED_IN_PROCESS
                ));

        log.debug("[PIX FLOW - Complete] Batch settlement processed. requested={}, settled={}",
                paymentIds.size(), settledPayments.size());
    }

    private void processRejectedPayment(PaymentTransaction paymentTransaction) {
        log.warn("[PIX FLOW - Rejection] Processing rejected payment. Payment ID: {}", 
                paymentTransaction.getPaymentId());
        
        paymentTransactionRepository.updateStatus(paymentTransaction.getPaymentId(), PaymentStatus.REJECTED);
        log.debug("[PIX FLOW - Rejection] Sending rejection notification to PSP Pagador");
        notificationService.sendRejectionNotification(paymentTransaction);
    }
}
