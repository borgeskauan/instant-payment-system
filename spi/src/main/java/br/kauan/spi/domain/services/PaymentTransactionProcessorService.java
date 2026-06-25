package br.kauan.spi.domain.services;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.domain.services.notification.NotificationService;
import br.kauan.spi.domain.services.tracing.SpiTraceEvent;
import br.kauan.spi.domain.services.tracing.SpiTraceRecorder;
import br.kauan.spi.port.input.PaymentTransactionProcessorUseCase;
import br.kauan.spi.port.output.PaymentTransactionRepository;
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
    public void processTransactions(List<PaymentTransactionCommand> transactions) {
        if (transactions.isEmpty()) {
            return;
        }

        log.debug("[PIX FLOW - Step 3] SPI received transaction requests. payments={}",
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
    public void processStatusReports(List<StatusReportCommand> statusReports) {
        StatusReportGroups statusReportGroups = groupStatusReports(statusReports);

        if (!statusReportGroups.acceptedPaymentIds().isEmpty()) {
            processAcceptedPayments(statusReportGroups.acceptedPaymentIds());
        }

        if (!statusReportGroups.rejectedReports().isEmpty()) {
            processRejectedStatusReports(statusReportGroups.rejectedReports());
        }
    }

    private StatusReportGroups groupStatusReports(List<StatusReportCommand> statusReports) {
        List<String> acceptedPaymentIds = new ArrayList<>(statusReports.size());
        List<StatusReportCommand> rejectedReports = new ArrayList<>();
        for (StatusReportCommand statusReport : statusReports) {
            if (statusReport.getStatus() == PaymentStatus.ACCEPTED_IN_PROCESS) {
                acceptedPaymentIds.add(statusReport.getOriginalPaymentId());
            } else if (statusReport.getStatus() == PaymentStatus.REJECTED) {
                rejectedReports.add(statusReport);
            }
        }
        return new StatusReportGroups(acceptedPaymentIds, rejectedReports);
    }

    private void processRejectedStatusReports(List<StatusReportCommand> statusReports) {
        List<PaymentTransactionCommand> rejectedPayments = new ArrayList<>(statusReports.size());
        List<String> rejectedPaymentIds = new ArrayList<>(statusReports.size());

        for (StatusReportCommand statusReport : statusReports) {
            log.debug("[PIX FLOW - Step 5] SPI received status report. Payment ID: {}, Status: {}",
                    statusReport.getOriginalPaymentId(), statusReport.getStatus());

            var paymentTransaction = paymentTransactionRepository.findById(statusReport.getOriginalPaymentId())
                    .orElseThrow(); // TODO: Use a better exception here
            log.warn("[PIX FLOW - Step 5] Payment rejected by PSP Recebedor");
            rejectedPaymentIds.add(paymentTransaction.getPaymentId());
            rejectedPayments.add(paymentTransaction);
        }

        paymentTransactionRepository.updateStatuses(rejectedPaymentIds, PaymentStatus.REJECTED);

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
            paymentTransactionRepository.updateStatuses(
                    settlementResult.notSettledPaymentIds(),
                    PaymentStatus.ACCEPTED_IN_PROCESS
            );
        }

        log.debug("[PIX FLOW - Complete] Settlement processed. requested={}, settledOrAlreadySettled={}",
                paymentIds.size(), settledOrAlreadySettledPayments.size());
    }

    private record StatusReportGroups(
            List<String> acceptedPaymentIds,
            List<StatusReportCommand> rejectedReports
    ) {
    }
}
