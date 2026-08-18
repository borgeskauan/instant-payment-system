package br.kauan.spi.domain.services;

import br.kauan.spi.domain.entity.security.AuthenticatedPaymentRequest;
import br.kauan.spi.domain.entity.security.AuthenticatedStatusReport;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.domain.services.audit.PaymentAuditService;
import br.kauan.spi.domain.services.notification.NotificationObligationService;
import br.kauan.spi.domain.services.tracing.SpiTraceEvent;
import br.kauan.spi.domain.services.tracing.SpiTraceRecorder;
import br.kauan.spi.port.input.PaymentTransactionProcessorUseCase;
import br.kauan.spi.port.input.StatusReportProcessingResult;
import br.kauan.spi.port.output.PaymentTransactionRepository;
import br.kauan.spi.port.output.PaymentTransactionPersistenceResult;
import br.kauan.spi.port.output.StatusReportPersistenceResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class PaymentTransactionProcessorService implements PaymentTransactionProcessorUseCase {

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PaymentAuditService paymentAuditService;
    private final NotificationObligationService notificationObligationService;
    private final SpiTraceRecorder traceRecorder;

    public PaymentTransactionProcessorService(
            PaymentTransactionRepository paymentTransactionRepository,
            PaymentAuditService paymentAuditService,
            NotificationObligationService notificationObligationService,
            SpiTraceRecorder traceRecorder
    ) {
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.paymentAuditService = paymentAuditService;
        this.notificationObligationService = notificationObligationService;
        this.traceRecorder = traceRecorder;
    }

    @Override
    @Transactional
    public PaymentTransactionPersistenceResult processTransactions(List<AuthenticatedPaymentRequest> transactions) {
        if (transactions.isEmpty()) {
            return new PaymentTransactionPersistenceResult(List.of(), List.of(), List.of(), List.of(), List.of());
        }

        log.debug("[PIX FLOW - Step 3] SPI received transaction requests. payments={}",
                transactions.size());
        PaymentTransactionPersistenceResult persistenceResult =
                paymentTransactionRepository.storeAndClassifyIncomingPaymentRequests(transactions);

        paymentAuditService.storeCreationEvents(
                persistenceResult.createdPayments(),
                persistenceResult.rejectedPayments()
        );
        if (!persistenceResult.acceptanceRequests().isEmpty()) {
            notificationObligationService.storeAcceptanceObligations(persistenceResult.acceptanceRequests());
        }
        if (!persistenceResult.rejectedPayments().isEmpty()) {
            notificationObligationService.storeStatusObligations(
                    List.of(),
                    persistenceResult.rejectedPayments()
            );
        }
        for (var paymentTransaction : persistenceResult.createdPayments()) {
            traceRecorder.record(paymentTransaction.getPaymentId(), SpiTraceEvent.REQUEST_SAVED);
        }
        if (!persistenceResult.acceptanceRequests().isEmpty()) {
            for (var paymentTransaction : persistenceResult.acceptanceRequests()) {
                traceRecorder.record(paymentTransaction.getPaymentId(), SpiTraceEvent.ACCEPTANCE_NOTIFICATION_ENQUEUED);
            }
        }
        return persistenceResult;
    }

    @Override
    @Transactional
    public StatusReportProcessingResult processStatusReports(List<AuthenticatedStatusReport> statusReports) {
        StatusReportPersistenceResult persistenceResult =
                paymentTransactionRepository.classifyAndApplyIncomingStatusReports(statusReports);
        List<AuthenticatedStatusReport> divergentStatusReports =
                new ArrayList<>(persistenceResult.divergentStatusReports());
        List<AuthenticatedStatusReport> unauthorizedStatusReports =
                new ArrayList<>(persistenceResult.unauthorizedStatusReports());

        paymentAuditService.storeStatusEvents(
                persistenceResult.appliedStatusTransitions(),
                persistenceResult.settledPayments()
        );

        if (!persistenceResult.rejectedPayments().isEmpty()) {
            log.debug("[PIX FLOW - Rejection] Storing rejection obligations for PSP Pagador. payments={}",
                    persistenceResult.rejectedPayments().size());
        }

        if (!persistenceResult.settledPayments().isEmpty()
                || !persistenceResult.rejectedPayments().isEmpty()) {
            notificationObligationService.storeStatusObligations(
                    persistenceResult.settledPayments(),
                    persistenceResult.rejectedPayments()
            );
        }

        for (PaymentTransactionCommand paymentTransaction : persistenceResult.settledPayments()) {
            traceRecorder.record(paymentTransaction.getPaymentId(), SpiTraceEvent.SETTLEMENT_COMPLETED);
        }

        for (PaymentTransactionCommand paymentTransaction : persistenceResult.settledPayments()) {
            traceRecorder.record(
                    paymentTransaction.getPaymentId(),
                    SpiTraceEvent.CONFIRMATION_NOTIFICATION_ENQUEUED
            );
        }

        log.debug("[PIX FLOW - Complete] Status reports processed. settled={}, rejected={}",
                persistenceResult.settledPayments().size(),
                persistenceResult.rejectedPayments().size());

        return new StatusReportProcessingResult(divergentStatusReports, unauthorizedStatusReports);
    }

}
