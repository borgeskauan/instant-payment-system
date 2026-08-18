package br.kauan.spi.domain.services;

import br.kauan.spi.domain.entity.status.PaymentRejection;
import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.security.AuthenticatedPaymentRequest;
import br.kauan.spi.domain.entity.security.AuthenticatedStatusReport;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.domain.services.audit.PaymentAuditService;
import br.kauan.spi.domain.services.notification.NotificationObligationService;
import br.kauan.spi.domain.services.tracing.SpiTraceEvent;
import br.kauan.spi.domain.services.tracing.SpiTraceRecorder;
import br.kauan.spi.port.input.StatusReportProcessingResult;
import br.kauan.spi.port.output.PaymentTransactionPersistenceResult;
import br.kauan.spi.port.output.PaymentTransactionRepository;
import br.kauan.spi.port.output.PaymentStatusTransition;
import br.kauan.spi.port.output.StatusReportPersistenceResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentTransactionProcessorServiceTest {

    @Test
    void acceptedStatusSettlesWaitingPaymentDirectlyAndSendsConfirmationWhenSettlementSucceeds() {
        PaymentTransactionRepository paymentTransactionRepository = mock(PaymentTransactionRepository.class);
        PaymentAuditService auditService = mock(PaymentAuditService.class);
        NotificationObligationService notificationService = mock(NotificationObligationService.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        PaymentTransactionProcessorService service = new PaymentTransactionProcessorService(
                paymentTransactionRepository,
                auditService,
                notificationService,
                traceRecorder
        );
        StatusReportCommand statusReport = StatusReportCommand.builder()
                .originalPaymentId("E2E-1")
                .status(PaymentStatus.ACCEPTED_IN_PROCESS)
                .build();
        PaymentTransactionCommand paymentTransaction = paymentTransaction();
        PaymentStatusTransition transition = transition(
                paymentTransaction.getPaymentId(),
                PaymentStatus.ACCEPTED_AND_SETTLED
        );
        when(paymentTransactionRepository.classifyAndApplyIncomingStatusReports(authenticatedReports(statusReport)))
                .thenReturn(new StatusReportPersistenceResult(
                        List.of(paymentTransaction),
                        List.of(),
                        List.of(transition),
                        List.of(),
                        List.of()
                ));

        StatusReportProcessingResult result = service.processStatusReports(authenticatedReports(statusReport));

        verify(paymentTransactionRepository).classifyAndApplyIncomingStatusReports(authenticatedReports(statusReport));
        verify(auditService).storeStatusEvents(List.of(transition), List.of(paymentTransaction));
        verify(notificationService).storeStatusObligations(List.of(paymentTransaction), List.of());
        verify(traceRecorder).record("E2E-1", SpiTraceEvent.SETTLEMENT_COMPLETED);
        verify(traceRecorder).record("E2E-1", SpiTraceEvent.CONFIRMATION_NOTIFICATION_ENQUEUED);
        assertThat(result.divergentStatusReports()).isEmpty();
    }

    @Test
    void acceptedStatusesFromSamePollAreSettledTogether() {
        PaymentTransactionRepository paymentTransactionRepository = mock(PaymentTransactionRepository.class);
        PaymentAuditService auditService = mock(PaymentAuditService.class);
        NotificationObligationService notificationService = mock(NotificationObligationService.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        PaymentTransactionProcessorService service = new PaymentTransactionProcessorService(
                paymentTransactionRepository,
                auditService,
                notificationService,
                traceRecorder
        );
        StatusReportCommand firstReport = StatusReportCommand.builder()
                .originalPaymentId("E2E-1")
                .status(PaymentStatus.ACCEPTED_IN_PROCESS)
                .build();
        StatusReportCommand secondReport = StatusReportCommand.builder()
                .originalPaymentId("E2E-2")
                .status(PaymentStatus.ACCEPTED_IN_PROCESS)
                .build();
        PaymentTransactionCommand firstPayment = paymentTransaction("E2E-1");
        PaymentTransactionCommand secondPayment = paymentTransaction("E2E-2");
        List<PaymentStatusTransition> transitions = List.of(
                transition("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED),
                transition("E2E-2", PaymentStatus.ACCEPTED_AND_SETTLED)
        );
        when(paymentTransactionRepository.classifyAndApplyIncomingStatusReports(
                authenticatedReports(firstReport, secondReport)
        )).thenReturn(new StatusReportPersistenceResult(
                List.of(firstPayment, secondPayment),
                List.of(),
                transitions,
                List.of(),
                List.of()
        ));

        service.processStatusReports(authenticatedReports(firstReport, secondReport));

        verify(paymentTransactionRepository).classifyAndApplyIncomingStatusReports(
                authenticatedReports(firstReport, secondReport)
        );
        verify(notificationService).storeStatusObligations(List.of(firstPayment, secondPayment), List.of());
        verify(auditService).storeStatusEvents(transitions, List.of(firstPayment, secondPayment));
        verify(traceRecorder).record("E2E-1", SpiTraceEvent.SETTLEMENT_COMPLETED);
        verify(traceRecorder).record("E2E-1", SpiTraceEvent.CONFIRMATION_NOTIFICATION_ENQUEUED);
        verify(traceRecorder).record("E2E-2", SpiTraceEvent.SETTLEMENT_COMPLETED);
        verify(traceRecorder).record("E2E-2", SpiTraceEvent.CONFIRMATION_NOTIFICATION_ENQUEUED);
    }

    @Test
    void statusReportResultWithoutSettledPaymentsSkipsConfirmation() {
        PaymentTransactionRepository paymentTransactionRepository = mock(PaymentTransactionRepository.class);
        NotificationObligationService notificationService = mock(NotificationObligationService.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        PaymentTransactionProcessorService service = new PaymentTransactionProcessorService(
                paymentTransactionRepository,
                mock(PaymentAuditService.class),
                notificationService,
                traceRecorder
        );
        StatusReportCommand statusReport = StatusReportCommand.builder()
                .originalPaymentId("E2E-1")
                .status(PaymentStatus.ACCEPTED_IN_PROCESS)
                .build();
        when(paymentTransactionRepository.classifyAndApplyIncomingStatusReports(authenticatedReports(statusReport)))
                .thenReturn(new StatusReportPersistenceResult(
                        List.of(), List.of(), List.of(), List.of(), List.of()));

        service.processStatusReports(authenticatedReports(statusReport));

        verifyNoInteractions(notificationService);
    }

    @Test
    void rejectedStatusesSendNotificationsForConditionallyRejectedPayments() {
        PaymentTransactionRepository paymentTransactionRepository = mock(PaymentTransactionRepository.class);
        PaymentAuditService auditService = mock(PaymentAuditService.class);
        NotificationObligationService notificationService = mock(NotificationObligationService.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        PaymentTransactionProcessorService service = new PaymentTransactionProcessorService(
                paymentTransactionRepository,
                auditService,
                notificationService,
                traceRecorder
        );
        StatusReportCommand firstReport = StatusReportCommand.builder()
                .originalPaymentId("E2E-1")
                .status(PaymentStatus.REJECTED)
                .build();
        StatusReportCommand secondReport = StatusReportCommand.builder()
                .originalPaymentId("E2E-2")
                .status(PaymentStatus.REJECTED)
                .build();
        PaymentTransactionCommand firstPayment = paymentTransaction("E2E-1");
        PaymentTransactionCommand secondPayment = paymentTransaction("E2E-2");
        List<PaymentRejection> rejections = List.of(
                new PaymentRejection(firstPayment, null),
                new PaymentRejection(secondPayment, null)
        );
        List<PaymentStatusTransition> transitions = List.of(
                transition("E2E-1", PaymentStatus.REJECTED),
                transition("E2E-2", PaymentStatus.REJECTED)
        );
        when(paymentTransactionRepository.classifyAndApplyIncomingStatusReports(
                authenticatedReports(firstReport, secondReport)
        ))
                .thenReturn(new StatusReportPersistenceResult(
                        List.of(),
                        rejections,
                        transitions,
                        List.of(),
                        List.of()
                ));

        service.processStatusReports(authenticatedReports(firstReport, secondReport));

        verify(paymentTransactionRepository).classifyAndApplyIncomingStatusReports(
                authenticatedReports(firstReport, secondReport)
        );
        verify(notificationService).storeStatusObligations(List.of(), rejections);
        verify(auditService).storeStatusEvents(transitions, List.of());
    }

    @Test
    void duplicateIdenticalStatusReportsArePassedToRepositoryForBatchLocalClassification() {
        PaymentTransactionRepository paymentTransactionRepository = mock(PaymentTransactionRepository.class);
        PaymentTransactionProcessorService service = new PaymentTransactionProcessorService(
                paymentTransactionRepository,
                mock(PaymentAuditService.class),
                mock(NotificationObligationService.class),
                mock(SpiTraceRecorder.class)
        );
        StatusReportCommand first = StatusReportCommand.builder()
                .originalPaymentId("E2E-1")
                .status(PaymentStatus.ACCEPTED_IN_PROCESS)
                .build();
        StatusReportCommand repeated = StatusReportCommand.builder()
                .originalPaymentId("E2E-1")
                .status(PaymentStatus.ACCEPTED_IN_PROCESS)
                .build();
        when(paymentTransactionRepository.classifyAndApplyIncomingStatusReports(authenticatedReports(first, repeated)))
                .thenReturn(new StatusReportPersistenceResult(
                        List.of(), List.of(), List.of(), List.of(), List.of()));

        StatusReportProcessingResult result = service.processStatusReports(authenticatedReports(first, repeated));

        verify(paymentTransactionRepository).classifyAndApplyIncomingStatusReports(authenticatedReports(first, repeated));
        assertThat(result.divergentStatusReports()).isEmpty();
    }

    @Test
    void conflictingStatusReportsFromSameBatchAreReturnedFromRepositoryAsDivergent() {
        PaymentTransactionRepository paymentTransactionRepository = mock(PaymentTransactionRepository.class);
        NotificationObligationService notificationService = mock(NotificationObligationService.class);
        PaymentTransactionProcessorService service = new PaymentTransactionProcessorService(
                paymentTransactionRepository,
                mock(PaymentAuditService.class),
                notificationService,
                mock(SpiTraceRecorder.class)
        );
        StatusReportCommand accepted = StatusReportCommand.builder()
                .originalPaymentId("E2E-1")
                .status(PaymentStatus.ACCEPTED_IN_PROCESS)
                .build();
        StatusReportCommand rejected = StatusReportCommand.builder()
                .originalPaymentId("E2E-1")
                .status(PaymentStatus.REJECTED)
                .build();
        StatusReportCommand other = StatusReportCommand.builder()
                .originalPaymentId("E2E-2")
                .status(PaymentStatus.ACCEPTED_IN_PROCESS)
                .build();
        PaymentTransactionCommand settledPayment = paymentTransaction("E2E-2");
        when(paymentTransactionRepository.classifyAndApplyIncomingStatusReports(
                authenticatedReports(accepted, rejected, other)
        ))
                .thenReturn(new StatusReportPersistenceResult(
                        List.of(settledPayment),
                        List.of(),
                        List.of(transition("E2E-2", PaymentStatus.ACCEPTED_AND_SETTLED)),
                        authenticatedReports(accepted, rejected),
                        List.of()
                ));

        StatusReportProcessingResult result =
                service.processStatusReports(authenticatedReports(accepted, rejected, other));

        assertThat(result.divergentStatusReports())
                .extracting(AuthenticatedStatusReport::command)
                .containsExactly(accepted, rejected);
        verify(paymentTransactionRepository).classifyAndApplyIncomingStatusReports(
                authenticatedReports(accepted, rejected, other)
        );
        verify(notificationService).storeStatusObligations(List.of(settledPayment), List.of());
    }

    @Test
    void repositoryDivergentStatusReportsAreReturnedToConsumer() {
        PaymentTransactionRepository paymentTransactionRepository = mock(PaymentTransactionRepository.class);
        PaymentTransactionProcessorService service = new PaymentTransactionProcessorService(
                paymentTransactionRepository,
                mock(PaymentAuditService.class),
                mock(NotificationObligationService.class),
                mock(SpiTraceRecorder.class)
        );
        StatusReportCommand divergent = StatusReportCommand.builder()
                .originalPaymentId("E2E-1")
                .status(PaymentStatus.REJECTED)
                .build();
        when(paymentTransactionRepository.classifyAndApplyIncomingStatusReports(authenticatedReports(divergent)))
                .thenReturn(new StatusReportPersistenceResult(
                        List.of(),
                        List.of(),
                        List.of(),
                        authenticatedReports(divergent),
                        List.of()
                ));

        StatusReportProcessingResult result = service.processStatusReports(authenticatedReports(divergent));

        assertThat(result.divergentStatusReports())
                .extracting(AuthenticatedStatusReport::command)
                .containsExactly(divergent);
    }

    @Test
    void transactionRequestSavesPaymentsAndEnqueuesAcceptanceNotifications() {
        PaymentTransactionRepository paymentTransactionRepository = mock(PaymentTransactionRepository.class);
        PaymentAuditService auditService = mock(PaymentAuditService.class);
        NotificationObligationService notificationService = mock(NotificationObligationService.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        PaymentTransactionProcessorService service = new PaymentTransactionProcessorService(
                paymentTransactionRepository,
                auditService,
                notificationService,
                traceRecorder
        );
        PaymentTransactionCommand firstPayment = paymentTransaction("E2E-1", "10000001", "20000001");
        PaymentTransactionCommand secondPayment = paymentTransaction("E2E-2", "10000002", "20000002");
        when(paymentTransactionRepository.storeAndClassifyIncomingPaymentRequests(
                authenticatedPayments(firstPayment, secondPayment)
        )).thenReturn(new PaymentTransactionPersistenceResult(
                List.of(firstPayment, secondPayment),
                List.of(firstPayment, secondPayment),
                List.of(),
                List.of(),
                List.of()
        ));

        service.processTransactions(authenticatedPayments(firstPayment, secondPayment));

        verify(paymentTransactionRepository).storeAndClassifyIncomingPaymentRequests(
                authenticatedPayments(firstPayment, secondPayment)
        );
        verify(auditService).storeCreationEvents(List.of(firstPayment, secondPayment), List.of());
        verify(traceRecorder).record("E2E-1", SpiTraceEvent.REQUEST_SAVED);
        verify(traceRecorder).record("E2E-2", SpiTraceEvent.REQUEST_SAVED);
        verify(notificationService).storeAcceptanceObligations(List.of(firstPayment, secondPayment));
        verify(traceRecorder).record("E2E-1", SpiTraceEvent.ACCEPTANCE_NOTIFICATION_ENQUEUED);
        verify(traceRecorder).record("E2E-2", SpiTraceEvent.ACCEPTANCE_NOTIFICATION_ENQUEUED);
    }

    @Test
    void transactionRequestOnlyNotifiesAcceptanceRequestsReturnedByRepository() {
        PaymentTransactionRepository paymentTransactionRepository = mock(PaymentTransactionRepository.class);
        PaymentAuditService auditService = mock(PaymentAuditService.class);
        NotificationObligationService notificationService = mock(NotificationObligationService.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        PaymentTransactionProcessorService service = new PaymentTransactionProcessorService(
                paymentTransactionRepository,
                auditService,
                notificationService,
                traceRecorder
        );
        PaymentTransactionCommand waitingDuplicate = paymentTransaction("E2E-WAITING", "10000001", "20000001");
        PaymentTransactionCommand advancedDuplicate = paymentTransaction("E2E-SETTLED", "10000002", "20000002");
        PaymentTransactionCommand divergentDuplicate = paymentTransaction("E2E-DIVERGENT", "10000003", "20000003");
        when(paymentTransactionRepository.storeAndClassifyIncomingPaymentRequests(
                authenticatedPayments(waitingDuplicate, advancedDuplicate, divergentDuplicate)
        )).thenReturn(new PaymentTransactionPersistenceResult(
                List.of(waitingDuplicate),
                List.of(),
                List.of(),
                authenticatedPayments(divergentDuplicate),
                List.of()
        ));

        PaymentTransactionPersistenceResult result = service.processTransactions(authenticatedPayments(
                waitingDuplicate,
                advancedDuplicate,
                divergentDuplicate
        ));

        verify(notificationService).storeAcceptanceObligations(List.of(waitingDuplicate));
        verify(auditService).storeCreationEvents(List.of(), List.of());
        verify(traceRecorder).record("E2E-WAITING", SpiTraceEvent.ACCEPTANCE_NOTIFICATION_ENQUEUED);
        verify(traceRecorder, never()).record("E2E-SETTLED", SpiTraceEvent.ACCEPTANCE_NOTIFICATION_ENQUEUED);
        verify(traceRecorder, never()).record("E2E-DIVERGENT", SpiTraceEvent.ACCEPTANCE_NOTIFICATION_ENQUEUED);
        org.assertj.core.api.Assertions.assertThat(result.divergentDuplicates())
                .extracting(AuthenticatedPaymentRequest::command)
                .containsExactly(divergentDuplicate);
    }

    @Test
    void transactionRequestStoresIngressRejectionAuditAndPayerNotification() {
        PaymentTransactionRepository repository = mock(PaymentTransactionRepository.class);
        PaymentAuditService auditService = mock(PaymentAuditService.class);
        NotificationObligationService notificationService = mock(NotificationObligationService.class);
        PaymentTransactionProcessorService service = new PaymentTransactionProcessorService(
                repository,
                auditService,
                notificationService,
                mock(SpiTraceRecorder.class)
        );
        PaymentTransactionCommand payment = paymentTransaction("E2E-NO-FUNDS");
        PaymentRejection rejection = new PaymentRejection(
                payment,
                br.kauan.spi.domain.entity.status.PaymentRejectionReason.INSUFFICIENT_FUNDS
        );
        List<AuthenticatedPaymentRequest> requests = authenticatedPayments(payment);
        when(repository.storeAndClassifyIncomingPaymentRequests(requests))
                .thenReturn(new PaymentTransactionPersistenceResult(
                        List.of(),
                        List.of(payment),
                        List.of(rejection),
                        List.of(),
                        List.of()
                ));

        service.processTransactions(requests);

        verify(auditService).storeCreationEvents(List.of(payment), List.of(rejection));
        verify(notificationService).storeStatusObligations(List.of(), List.of(rejection));
        verify(notificationService, never()).storeAcceptanceObligations(org.mockito.ArgumentMatchers.anyList());
    }

    private static PaymentTransactionCommand paymentTransaction() {
        return paymentTransaction("E2E-1");
    }

    private static PaymentStatusTransition transition(String paymentId, PaymentStatus resultingStatus) {
        return new PaymentStatusTransition(
                paymentId,
                PaymentStatus.WAITING_ACCEPTANCE,
                resultingStatus
        );
    }

    private static PaymentTransactionCommand paymentTransaction(String paymentId) {
        return paymentTransaction(paymentId, "10000001", "20000001");
    }

    private static PaymentTransactionCommand paymentTransaction(String paymentId, String senderBankCode, String receiverBankCode) {
        return PaymentTransactionCommand.builder()
                .paymentId(paymentId)
                .amountCents(1000L)
                .sender(party(senderBankCode))
                .receiver(party(receiverBankCode))
                .build();
    }

    private static List<AuthenticatedPaymentRequest> authenticatedPayments(
            PaymentTransactionCommand... payments
    ) {
        List<AuthenticatedPaymentRequest> authenticatedPayments = new ArrayList<>(payments.length);
        for (int ordinal = 0; ordinal < payments.length; ordinal++) {
            PaymentTransactionCommand payment = payments[ordinal];
            authenticatedPayments.add(new AuthenticatedPaymentRequest(
                    ordinal,
                    payment.getSender().getAccount().getBankCode(),
                    payment
            ));
        }
        return authenticatedPayments;
    }

    private static List<AuthenticatedStatusReport> authenticatedReports(
            StatusReportCommand... reports
    ) {
        List<AuthenticatedStatusReport> authenticatedReports = new ArrayList<>(reports.length);
        for (int ordinal = 0; ordinal < reports.length; ordinal++) {
            authenticatedReports.add(new AuthenticatedStatusReport(
                    ordinal,
                    "20000001",
                    reports[ordinal]
            ));
        }
        return authenticatedReports;
    }

    private static Party party(String bankCode) {
        return Party.builder()
                .account(BankAccount.builder()
                        .bankCode(bankCode)
                        .number("1")
                        .branch("1")
                        .type(BankAccountType.CHECKING)
                        .build())
                .build();
    }
}
