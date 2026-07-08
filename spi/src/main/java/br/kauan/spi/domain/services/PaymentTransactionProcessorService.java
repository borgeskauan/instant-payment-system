package br.kauan.spi.domain.services;

import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.domain.services.notification.NotificationService;
import br.kauan.spi.domain.services.tracing.SpiTraceEvent;
import br.kauan.spi.domain.services.tracing.SpiTraceRecorder;
import br.kauan.spi.port.input.PaymentTransactionProcessorUseCase;
import br.kauan.spi.port.input.StatusReportProcessingResult;
import br.kauan.spi.port.output.PaymentTransactionRepository;
import br.kauan.spi.port.output.PaymentTransactionPersistenceResult;
import br.kauan.spi.port.output.StatusReportPersistenceResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

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
    public PaymentTransactionPersistenceResult processTransactions(List<PaymentTransactionCommand> transactions) {
        if (transactions.isEmpty()) {
            return new PaymentTransactionPersistenceResult(List.of(), List.of());
        }

        log.debug("[PIX FLOW - Step 3] SPI received transaction requests. payments={}",
                transactions.size());
        PaymentTransactionPersistenceResult persistenceResult =
                paymentTransactionRepository.storeAndClassifyIncomingPaymentRequests(transactions);

        for (var paymentTransaction : persistenceResult.acceptanceRequests()) {
            traceRecorder.record(paymentTransaction.getPaymentId(), SpiTraceEvent.REQUEST_SAVED);
        }
        if (!persistenceResult.acceptanceRequests().isEmpty()) {
            notificationService.sendAcceptanceRequests(persistenceResult.acceptanceRequests());
            for (var paymentTransaction : persistenceResult.acceptanceRequests()) {
                traceRecorder.record(paymentTransaction.getPaymentId(), SpiTraceEvent.ACCEPTANCE_NOTIFICATION_ENQUEUED);
            }
        }
        return persistenceResult;
    }

    @Override
    public StatusReportProcessingResult processStatusReports(List<StatusReportCommand> statusReports) {
        StatusReportPersistenceResult persistenceResult =
                paymentTransactionRepository.classifyAndApplyIncomingStatusReports(statusReports);
        List<StatusReportCommand> divergentStatusReports = new ArrayList<>(persistenceResult.divergentStatusReports());

        if (!persistenceResult.acceptedPaymentIds().isEmpty()) {
            processAcceptedPayments(persistenceResult.acceptedPaymentIds());
        }

        if (!persistenceResult.rejectedPayments().isEmpty()) {
            processRejectedPayments(persistenceResult.rejectedPayments());
        }

        return new StatusReportProcessingResult(divergentStatusReports);
    }

    private void processRejectedPayments(List<PaymentTransactionCommand> rejectedPayments) {
        log.debug("[PIX FLOW - Rejection] Sending rejection notifications to PSP Pagador. payments={}",
                rejectedPayments.size());
        notificationService.sendRejectionNotifications(rejectedPayments);
    }

    private void processAcceptedPayments(List<String> paymentIds) {
        log.debug("[PIX FLOW - Step 6] SPI initiating settlement via PI accounts at BCB. payments={}",
                paymentIds.size());

        SettlementResult settlementResult = settlementService.tryMakeSettlements(paymentIds);
        List<PaymentTransactionCommand> settledOrAlreadySettledPayments = settlementResult.settledOrAlreadySettledPayments();

        for (PaymentTransactionCommand paymentTransaction : settledOrAlreadySettledPayments) {
            String paymentId = paymentTransaction.getPaymentId();
            traceRecorder.record(paymentId, SpiTraceEvent.SETTLEMENT_COMPLETED);
        }
        if (!settledOrAlreadySettledPayments.isEmpty()) {
            notificationService.sendConfirmationNotifications(settledOrAlreadySettledPayments);
            for (PaymentTransactionCommand paymentTransaction : settledOrAlreadySettledPayments) {
                String paymentId = paymentTransaction.getPaymentId();
                traceRecorder.record(paymentId, SpiTraceEvent.CONFIRMATION_NOTIFICATION_ENQUEUED);
            }
        }
        if (!settlementResult.notSettledPaymentIds().isEmpty()) {
            paymentTransactionRepository.markAcceptedInProcessIfWaitingAcceptance(
                    settlementResult.notSettledPaymentIds()
            );
        }

        log.debug("[PIX FLOW - Complete] Settlement processed. requested={}, settledOrAlreadySettled={}",
                paymentIds.size(), settledOrAlreadySettledPayments.size());
    }

}
