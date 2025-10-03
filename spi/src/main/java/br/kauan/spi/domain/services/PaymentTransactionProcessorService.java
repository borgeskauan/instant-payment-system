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
        paymentTransactionRepository.saveTransaction(paymentTransaction, PaymentStatus.WAITING_ACCEPTANCE);

        notificationService.sendAcceptanceRequest(Utils.getBankCode(paymentTransaction.getReceiver()), paymentTransaction);
    }

    private void processStatusReport(StatusReport statusReport) {
        var paymentTransaction = paymentTransactionRepository.findById(statusReport.getOriginalPaymentId())
                .orElseThrow(); // TODO: Use a better exception here

        switch (statusReport.getStatus()) {
            case ACCEPTED_IN_PROCESS -> processAcceptedPayment(paymentTransaction);
            case REJECTED -> processRejectedPayment(paymentTransaction);
        }
    }

    private void processAcceptedPayment(PaymentTransaction paymentTransaction) {
        try {
            paymentTransactionRepository.saveTransaction(paymentTransaction, PaymentStatus.ACCEPTED_IN_PROCESS);

            settlementService.makeSettlement(paymentTransaction);

            notificationService.sendConfirmationNotification(paymentTransaction);

            paymentTransactionRepository.saveTransaction(paymentTransaction, PaymentStatus.ACCEPTED_AND_SETTLED);

        } catch (Exception e) {
            log.error("An error ocurred while processing the payment with ID {}", paymentTransaction.getPaymentId(), e);

            // TODO: Save as pending and send to retry queue instead of rejecting.
            processRejectedPayment(paymentTransaction);
        }
    }

    private void processRejectedPayment(PaymentTransaction paymentTransaction) {
        paymentTransactionRepository.saveTransaction(paymentTransaction, PaymentStatus.REJECTED);

        notificationService.sendRejectionNotification(paymentTransaction);
    }
}
