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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTransactionProcessorService implements PaymentTransactionProcessorUseCase {

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final NotificationService notificationService;
    private final SettlementService settlementService;

    @Override
    public Mono<Void> processTransactionBatch(String ispb, PaymentBatch transactionBatch) {
        return Flux.fromIterable(transactionBatch.getTransactions())
                .flatMap(this::processTransaction)
                .then();
    }

    @Override
    public Mono<Void> processStatusBatch(String ispb, StatusBatch statusBatch) {
        return Flux.fromIterable(statusBatch.getStatusReports())
                .flatMap(this::processStatusReport)
                .then();
    }

    private Mono<Void> processTransaction(PaymentTransaction paymentTransaction) {
        return paymentTransactionRepository.createTransaction(paymentTransaction, PaymentStatus.WAITING_ACCEPTANCE)
                .then(notificationService.sendAcceptanceRequest(
                        Utils.getBankCode(paymentTransaction.getReceiver()),
                        paymentTransaction
                ));
    }

    private Mono<Void> processStatusReport(StatusReport statusReport) {
        return paymentTransactionRepository.findById(statusReport.getOriginalPaymentId())
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Payment transaction not found: " + statusReport.getOriginalPaymentId())))
                .flatMap(paymentTransaction -> switch (statusReport.getStatus()) {
                    case ACCEPTED_IN_PROCESS -> processAcceptedPayment(paymentTransaction);
                    case REJECTED -> processRejectedPayment(paymentTransaction);
                    default -> {
                        log.warn("Unknown status: {}", statusReport.getStatus());
                        yield Mono.empty();
                    }
                });
    }

    private Mono<Void> processAcceptedPayment(PaymentTransaction paymentTransaction) {
        return paymentTransactionRepository.updateTransaction(paymentTransaction, PaymentStatus.ACCEPTED_IN_PROCESS)
                .then(settlementService.makeSettlement(paymentTransaction))
                .then(notificationService.sendConfirmationNotification(paymentTransaction))
                .then(paymentTransactionRepository.updateTransaction(paymentTransaction, PaymentStatus.ACCEPTED_AND_SETTLED))
                .onErrorResume(e -> {
                    log.error("An error occurred while processing the payment with ID {}",
                            paymentTransaction.getPaymentId(), e);
                    // TODO: Save as pending and send to retry queue instead of rejecting.
                    return processRejectedPayment(paymentTransaction);
                });
    }

    private Mono<Void> processRejectedPayment(PaymentTransaction paymentTransaction) {
        return paymentTransactionRepository.updateTransaction(paymentTransaction, PaymentStatus.REJECTED)
                .then(notificationService.sendRejectionNotification(paymentTransaction));
    }
}