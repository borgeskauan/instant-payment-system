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
        for (var statusReport : statusBatch.getStatusReports()) {
            processStatusReport(statusReport);
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

    private void processStatusReport(StatusReport statusReport) {
        log.debug("[PIX FLOW - Step 5] SPI received status report. Payment ID: {}, Status: {}", 
                statusReport.getOriginalPaymentId(), statusReport.getStatus());

        switch (statusReport.getStatus()) {
            case ACCEPTED_IN_PROCESS -> {
                log.debug("[PIX FLOW - Step 5] Payment accepted by PSP Recebedor. Proceeding with settlement.");
                processAcceptedPayment(statusReport.getOriginalPaymentId());
            }
            case REJECTED -> {
                var paymentTransaction = paymentTransactionRepository.findById(statusReport.getOriginalPaymentId())
                        .orElseThrow(); // TODO: Use a better exception here
                log.warn("[PIX FLOW - Step 5] Payment rejected by PSP Recebedor");
                processRejectedPayment(paymentTransaction);
            }
        }
    }

    private void processAcceptedPayment(String paymentId) {
        try {
            log.debug("[PIX FLOW - Step 6] SPI initiating settlement via PI accounts at BCB. Payment ID: {}",
                    paymentId);

            var settledPayment = settlementService.tryMakeSettlement(paymentId);
            if (settledPayment.isEmpty()) {
                log.warn("[PIX FLOW - Step 6] Payment accepted by receiver, but settlement did not complete. Payment ID: {}",
                        paymentId);
                paymentTransactionRepository.updateStatus(paymentId, PaymentStatus.ACCEPTED_IN_PROCESS);
                return;
            }

            log.debug("[PIX FLOW - Step 6] Settlement completed successfully at SPI");
            traceRecorder.record(paymentId, SpiTraceEvent.SETTLEMENT_COMPLETED);

            log.debug("[PIX FLOW - Step 7] SPI sending confirmation notifications to both PSPs");
            var paymentTransaction = settledPayment.orElseThrow();
            notificationService.sendConfirmationNotification(paymentTransaction);
            traceRecorder.record(paymentId, SpiTraceEvent.CONFIRMATION_NOTIFICATION_ENQUEUED);

            log.debug("[PIX FLOW - Complete] Payment {} fully settled and confirmed", paymentId);

        } catch (Exception e) {
            log.error("[PIX FLOW - Error] An error occurred while settling the payment with ID {}",
                    paymentId, e);
            paymentTransactionRepository.updateStatus(paymentId, PaymentStatus.ACCEPTED_IN_PROCESS);
        }
    }

    private void processRejectedPayment(PaymentTransaction paymentTransaction) {
        log.warn("[PIX FLOW - Rejection] Processing rejected payment. Payment ID: {}", 
                paymentTransaction.getPaymentId());
        
        paymentTransactionRepository.updateStatus(paymentTransaction.getPaymentId(), PaymentStatus.REJECTED);
        log.debug("[PIX FLOW - Rejection] Sending rejection notification to PSP Pagador");
        notificationService.sendRejectionNotification(paymentTransaction);
    }
}
