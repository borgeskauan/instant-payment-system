package br.kauan.spi.domain.services;

import br.kauan.spi.domain.entity.security.AuthenticatedPaymentRequest;
import br.kauan.spi.domain.entity.security.AuthenticatedStatusReport;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.domain.services.audit.PaymentAuditService;
import br.kauan.spi.domain.services.notification.NotificationObligationService;
import br.kauan.spi.domain.services.tracing.SpiPaymentStage;
import br.kauan.spi.domain.services.tracing.SpiPaymentStageEvent;
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

    public PaymentTransactionProcessorService(
            PaymentTransactionRepository paymentTransactionRepository,
            PaymentAuditService paymentAuditService,
            NotificationObligationService notificationObligationService
    ) {
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.paymentAuditService = paymentAuditService;
        this.notificationObligationService = notificationObligationService;
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

        paymentAuditService.storeAdmissionEvents(
                persistenceResult.createdPayments(),
                persistenceResult.rejectedPayments()
        );
        if (!persistenceResult.acceptanceRequests().isEmpty()
                || !persistenceResult.rejectedPayments().isEmpty()) {
            notificationObligationService.storeTransactionObligations(
                    persistenceResult.acceptanceRequests(),
                    persistenceResult.rejectedPayments()
            );
        }
        for (var paymentTransaction : persistenceResult.createdPayments()) {
            SpiPaymentStageEvent.record(paymentTransaction.getPaymentId(), SpiPaymentStage.REQUEST_SAVED);
        }
        if (!persistenceResult.acceptanceRequests().isEmpty()) {
            for (var paymentTransaction : persistenceResult.acceptanceRequests()) {
                SpiPaymentStageEvent.record(
                        paymentTransaction.getPaymentId(),
                        SpiPaymentStage.ACCEPTANCE_NOTIFICATION_ENQUEUED
                );
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

        paymentAuditService.storeOutcomeEvents(
                persistenceResult.settledPayments(),
                persistenceResult.rejectedPayments()
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
            SpiPaymentStageEvent.record(paymentTransaction.getPaymentId(), SpiPaymentStage.SETTLEMENT_COMPLETED);
        }

        for (PaymentTransactionCommand paymentTransaction : persistenceResult.settledPayments()) {
            SpiPaymentStageEvent.record(
                    paymentTransaction.getPaymentId(),
                    SpiPaymentStage.CONFIRMATION_NOTIFICATION_ENQUEUED
            );
        }

        log.debug("[PIX FLOW - Complete] Status reports processed. settled={}, rejected={}",
                persistenceResult.settledPayments().size(),
                persistenceResult.rejectedPayments().size());

        return new StatusReportProcessingResult(divergentStatusReports, unauthorizedStatusReports);
    }

}
