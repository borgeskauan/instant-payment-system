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
import br.kauan.spi.port.input.StatusReportProcessingResult;
import br.kauan.spi.port.output.PaymentTransactionPersistenceResult;
import br.kauan.spi.port.output.PaymentTransactionRepository;
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
        PaymentTransactionProcessorService service = new PaymentTransactionProcessorService(
                paymentTransactionRepository,
                auditService,
                notificationService
        );
        StatusReportCommand statusReport = StatusReportCommand.builder()
                .originalPaymentId("E2E-1")
                .status(PaymentStatus.ACCEPTED_IN_PROCESS)
                .build();
        PaymentTransactionCommand paymentTransaction = paymentTransaction();
        when(paymentTransactionRepository.classifyAndApplyIncomingStatusReports(authenticatedReports(statusReport)))
                .thenReturn(new StatusReportPersistenceResult(
                        List.of(paymentTransaction),
                        List.of(),
                        List.of(),
                        List.of()
                ));

        StatusReportProcessingResult result = service.processStatusReports(authenticatedReports(statusReport));

        verify(paymentTransactionRepository).classifyAndApplyIncomingStatusReports(authenticatedReports(statusReport));
        verify(auditService).storeOutcomeEvents(List.of(paymentTransaction), List.of());
        verify(notificationService).storeStatusObligations(List.of(paymentTransaction), List.of());
        assertThat(result.divergentStatusReports()).isEmpty();
    }

    @Test
    void acceptedStatusesFromSamePollAreSettledTogether() {
        PaymentTransactionRepository paymentTransactionRepository = mock(PaymentTransactionRepository.class);
        PaymentAuditService auditService = mock(PaymentAuditService.class);
        NotificationObligationService notificationService = mock(NotificationObligationService.class);
        PaymentTransactionProcessorService service = new PaymentTransactionProcessorService(
                paymentTransactionRepository,
                auditService,
                notificationService
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
        when(paymentTransactionRepository.classifyAndApplyIncomingStatusReports(
                authenticatedReports(firstReport, secondReport)
        )).thenReturn(new StatusReportPersistenceResult(
                List.of(firstPayment, secondPayment),
                List.of(),
                List.of(),
                List.of()
        ));

        service.processStatusReports(authenticatedReports(firstReport, secondReport));

        verify(paymentTransactionRepository).classifyAndApplyIncomingStatusReports(
                authenticatedReports(firstReport, secondReport)
        );
        verify(notificationService).storeStatusObligations(List.of(firstPayment, secondPayment), List.of());
        verify(auditService).storeOutcomeEvents(List.of(firstPayment, secondPayment), List.of());
    }

    @Test
    void statusReportResultWithoutSettledPaymentsSkipsConfirmation() {
        PaymentTransactionRepository paymentTransactionRepository = mock(PaymentTransactionRepository.class);
        NotificationObligationService notificationService = mock(NotificationObligationService.class);
        PaymentTransactionProcessorService service = new PaymentTransactionProcessorService(
                paymentTransactionRepository,
                mock(PaymentAuditService.class),
                notificationService
        );
        StatusReportCommand statusReport = StatusReportCommand.builder()
                .originalPaymentId("E2E-1")
                .status(PaymentStatus.ACCEPTED_IN_PROCESS)
                .build();
        when(paymentTransactionRepository.classifyAndApplyIncomingStatusReports(authenticatedReports(statusReport)))
                .thenReturn(new StatusReportPersistenceResult(
                        List.of(), List.of(), List.of(), List.of()));

        service.processStatusReports(authenticatedReports(statusReport));

        verifyNoInteractions(notificationService);
    }

    @Test
    void rejectedStatusesSendNotificationsForConditionallyRejectedPayments() {
        PaymentTransactionRepository paymentTransactionRepository = mock(PaymentTransactionRepository.class);
        PaymentAuditService auditService = mock(PaymentAuditService.class);
        NotificationObligationService notificationService = mock(NotificationObligationService.class);
        PaymentTransactionProcessorService service = new PaymentTransactionProcessorService(
                paymentTransactionRepository,
                auditService,
                notificationService
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
        when(paymentTransactionRepository.classifyAndApplyIncomingStatusReports(
                authenticatedReports(firstReport, secondReport)
        ))
                .thenReturn(new StatusReportPersistenceResult(
                        List.of(),
                        rejections,
                        List.of(),
                        List.of()
                ));

        service.processStatusReports(authenticatedReports(firstReport, secondReport));

        verify(paymentTransactionRepository).classifyAndApplyIncomingStatusReports(
                authenticatedReports(firstReport, secondReport)
        );
        verify(notificationService).storeStatusObligations(List.of(), rejections);
        verify(auditService).storeOutcomeEvents(List.of(), rejections);
    }

    @Test
    void duplicateIdenticalStatusReportsArePassedToRepositoryForBatchLocalClassification() {
        PaymentTransactionRepository paymentTransactionRepository = mock(PaymentTransactionRepository.class);
        PaymentTransactionProcessorService service = new PaymentTransactionProcessorService(
                paymentTransactionRepository,
                mock(PaymentAuditService.class),
                mock(NotificationObligationService.class)
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
                        List.of(), List.of(), List.of(), List.of()));

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
                notificationService
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
                mock(NotificationObligationService.class)
        );
        StatusReportCommand divergent = StatusReportCommand.builder()
                .originalPaymentId("E2E-1")
                .status(PaymentStatus.REJECTED)
                .build();
        when(paymentTransactionRepository.classifyAndApplyIncomingStatusReports(authenticatedReports(divergent)))
                .thenReturn(new StatusReportPersistenceResult(
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
        PaymentTransactionProcessorService service = new PaymentTransactionProcessorService(
                paymentTransactionRepository,
                auditService,
                notificationService
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
        verify(auditService).storeAdmissionEvents(List.of(firstPayment, secondPayment), List.of());
        verify(notificationService).storeTransactionObligations(
                List.of(firstPayment, secondPayment),
                List.of()
        );
    }

    @Test
    void transactionRequestOnlyNotifiesAcceptanceRequestsReturnedByRepository() {
        PaymentTransactionRepository paymentTransactionRepository = mock(PaymentTransactionRepository.class);
        PaymentAuditService auditService = mock(PaymentAuditService.class);
        NotificationObligationService notificationService = mock(NotificationObligationService.class);
        PaymentTransactionProcessorService service = new PaymentTransactionProcessorService(
                paymentTransactionRepository,
                auditService,
                notificationService
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

        verify(notificationService).storeTransactionObligations(List.of(waitingDuplicate), List.of());
        verify(auditService).storeAdmissionEvents(List.of(), List.of());
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
                notificationService
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

        verify(auditService).storeAdmissionEvents(List.of(payment), List.of(rejection));
        verify(notificationService).storeTransactionObligations(List.of(), List.of(rejection));
        verify(notificationService, never()).storeStatusObligations(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList()
        );
    }

    @Test
    void transactionRequestCombinesAcceptanceAndRejectionNotificationPersistence() {
        PaymentTransactionRepository repository = mock(PaymentTransactionRepository.class);
        NotificationObligationService notificationService = mock(NotificationObligationService.class);
        PaymentTransactionProcessorService service = new PaymentTransactionProcessorService(
                repository,
                mock(PaymentAuditService.class),
                notificationService
        );
        PaymentTransactionCommand accepted = paymentTransaction(
                "E2E-ACCEPTED",
                "10000001",
                "20000001"
        );
        PaymentTransactionCommand rejected = paymentTransaction(
                "E2E-REJECTED",
                "10000002",
                "20000002"
        );
        PaymentRejection rejection = new PaymentRejection(
                rejected,
                br.kauan.spi.domain.entity.status.PaymentRejectionReason.INSUFFICIENT_FUNDS
        );
        List<AuthenticatedPaymentRequest> requests = authenticatedPayments(accepted, rejected);
        when(repository.storeAndClassifyIncomingPaymentRequests(requests))
                .thenReturn(new PaymentTransactionPersistenceResult(
                        List.of(accepted),
                        List.of(accepted, rejected),
                        List.of(rejection),
                        List.of(),
                        List.of()
                ));

        service.processTransactions(requests);

        verify(notificationService).storeTransactionObligations(
                List.of(accepted),
                List.of(rejection)
        );
        verify(notificationService, never()).storeStatusObligations(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList()
        );
    }

    private static PaymentTransactionCommand paymentTransaction() {
        return paymentTransaction("E2E-1");
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
