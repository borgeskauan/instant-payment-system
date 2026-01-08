package br.kauan.spi.domain.services;

import br.kauan.spi.Utils;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.StatusBatch;
import br.kauan.spi.domain.entity.status.StatusReport;
import br.kauan.spi.domain.entity.transfer.PaymentBatch;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.domain.services.notification.NotificationService;
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

    public PaymentTransactionProcessorService(
            PaymentTransactionRepository paymentTransactionRepository,
            NotificationService notificationService,
            SettlementService settlementService
    ) {
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.notificationService = notificationService;
        this.settlementService = settlementService;
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
        log.info("[PIX FLOW - Step 3] SPI received transaction request. Payment ID: {}, Amount: {}", 
                paymentTransaction.getPaymentId(), paymentTransaction.getAmount());
        
        paymentTransactionRepository.saveTransaction(paymentTransaction, PaymentStatus.WAITING_ACCEPTANCE);
        log.debug("[PIX FLOW - Step 3] Transaction saved with status WAITING_ACCEPTANCE");

        String receiverBank = Utils.getBankCode(paymentTransaction.getReceiver());
        log.info("[PIX FLOW - Step 4] SPI forwarding acceptance request to PSP Recebedor (Bank: {})", receiverBank);
        notificationService.sendAcceptanceRequest(receiverBank, paymentTransaction);
    }

    private void processStatusReport(StatusReport statusReport) {
        log.info("[PIX FLOW - Step 5] SPI received status report. Payment ID: {}, Status: {}", 
                statusReport.getOriginalPaymentId(), statusReport.getStatus());
        
        var paymentTransaction = paymentTransactionRepository.findById(statusReport.getOriginalPaymentId())
                .orElseThrow(); // TODO: Use a better exception here

        switch (statusReport.getStatus()) {
            case ACCEPTED_IN_PROCESS -> {
                log.info("[PIX FLOW - Step 5] Payment accepted by PSP Recebedor. Proceeding with settlement.");
                processAcceptedPayment(paymentTransaction);
            }
            case REJECTED -> {
                log.warn("[PIX FLOW - Step 5] Payment rejected by PSP Recebedor");
                processRejectedPayment(paymentTransaction);
            }
        }
    }

    private void processAcceptedPayment(PaymentTransaction paymentTransaction) {
        try {
            paymentTransactionRepository.saveTransaction(paymentTransaction, PaymentStatus.ACCEPTED_IN_PROCESS);
            log.debug("[PIX FLOW - Step 6] Payment status updated to ACCEPTED_IN_PROCESS");

            log.info("[PIX FLOW - Step 6] SPI initiating settlement via PI accounts at BCB. Payment ID: {}, Amount: {}", 
                    paymentTransaction.getPaymentId(), paymentTransaction.getAmount());
            settlementService.makeSettlement(paymentTransaction);
            log.info("[PIX FLOW - Step 6] Settlement completed successfully at SPI");

            log.info("[PIX FLOW - Step 7] SPI sending confirmation notifications to both PSPs");
            notificationService.sendConfirmationNotification(paymentTransaction);

            paymentTransactionRepository.saveTransaction(paymentTransaction, PaymentStatus.ACCEPTED_AND_SETTLED);
            log.info("[PIX FLOW - Complete] Payment {} fully settled and confirmed", paymentTransaction.getPaymentId());

        } catch (Exception e) {
            log.error("[PIX FLOW - Error] An error occurred while processing the payment with ID {}", 
                    paymentTransaction.getPaymentId(), e);

            // TODO: Save as pending and send to retry queue instead of rejecting.
            processRejectedPayment(paymentTransaction);
        }
    }

    private void processRejectedPayment(PaymentTransaction paymentTransaction) {
        log.warn("[PIX FLOW - Rejection] Processing rejected payment. Payment ID: {}", 
                paymentTransaction.getPaymentId());
        
        paymentTransactionRepository.saveTransaction(paymentTransaction, PaymentStatus.REJECTED);
        log.info("[PIX FLOW - Rejection] Sending rejection notification to PSP Pagador");
        notificationService.sendRejectionNotification(paymentTransaction);
    }
}
