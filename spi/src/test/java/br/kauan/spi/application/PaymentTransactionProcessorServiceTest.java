package br.kauan.spi.application;

import br.kauan.spi.application.audit.PaymentAuditService;
import br.kauan.spi.application.notification.NotificationObligationService;
import br.kauan.spi.domain.entity.security.AuthenticatedPaymentRequest;
import br.kauan.spi.domain.entity.security.AuthenticatedStatusReport;
import br.kauan.spi.domain.entity.status.IncomingStatusReportCommand;
import br.kauan.spi.domain.entity.status.PaymentRejection;
import br.kauan.spi.domain.entity.status.PaymentSettlement;
import br.kauan.spi.domain.entity.status.StatusReasonCode;
import br.kauan.spi.domain.entity.status.StatusReportOutcome;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.port.output.PaymentTransactionPersistenceResult;
import br.kauan.spi.port.output.PaymentTransactionRepository;
import br.kauan.spi.port.output.StatusReportPersistenceResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentTransactionProcessorServiceTest {

    @Test
    void settledOutcomeKeepsReasonCodesThroughAuditAndNotification() {
        PaymentTransactionRepository repository = mock(PaymentTransactionRepository.class);
        PaymentAuditService auditService = mock(PaymentAuditService.class);
        NotificationObligationService notificationService = mock(NotificationObligationService.class);
        PaymentTransactionProcessorService service = service(repository, auditService, notificationService);
        IncomingStatusReportCommand report = accepted("E2E-1", "AC01");
        PaymentSettlement settlement = new PaymentSettlement(
                payment("E2E-1"),
                List.of(StatusReasonCode.of("AC01"))
        );
        when(repository.classifyAndApplyIncomingStatusReports(authenticatedReports(report)))
                .thenReturn(new StatusReportPersistenceResult(
                        List.of(settlement),
                        List.of(),
                        List.of(),
                        List.of()
                ));

        var result = service.processStatusReports(authenticatedReports(report));

        verify(auditService).storeOutcomeEvents(List.of(settlement), List.of());
        verify(notificationService).storeStatusObligations(List.of(settlement), List.of());
        assertThat(result.divergentStatusReports()).isEmpty();
    }

    @Test
    void receiverRejectionKeepsExternalCodesThroughAuditAndNotification() {
        PaymentTransactionRepository repository = mock(PaymentTransactionRepository.class);
        PaymentAuditService auditService = mock(PaymentAuditService.class);
        NotificationObligationService notificationService = mock(NotificationObligationService.class);
        PaymentTransactionProcessorService service = service(repository, auditService, notificationService);
        IncomingStatusReportCommand report = rejected("E2E-1", "AB03");
        PaymentRejection rejection = PaymentRejection.receiverRejected(
                payment("E2E-1"),
                List.of(StatusReasonCode.of("AB03"))
        );
        when(repository.classifyAndApplyIncomingStatusReports(authenticatedReports(report)))
                .thenReturn(new StatusReportPersistenceResult(
                        List.of(),
                        List.of(rejection),
                        List.of(),
                        List.of()
                ));

        service.processStatusReports(authenticatedReports(report));

        verify(auditService).storeOutcomeEvents(List.of(), List.of(rejection));
        verify(notificationService).storeStatusObligations(List.of(), List.of(rejection));
    }

    @Test
    void noNewOutcomeSkipsNotificationPersistence() {
        PaymentTransactionRepository repository = mock(PaymentTransactionRepository.class);
        NotificationObligationService notificationService = mock(NotificationObligationService.class);
        PaymentTransactionProcessorService service = service(
                repository,
                mock(PaymentAuditService.class),
                notificationService
        );
        IncomingStatusReportCommand report = accepted("E2E-1");
        when(repository.classifyAndApplyIncomingStatusReports(authenticatedReports(report)))
                .thenReturn(new StatusReportPersistenceResult(List.of(), List.of(), List.of(), List.of()));

        service.processStatusReports(authenticatedReports(report));

        verifyNoInteractions(notificationService);
    }

    @Test
    void divergentAndUnauthorizedReportsAreReturnedToTheConsumer() {
        PaymentTransactionRepository repository = mock(PaymentTransactionRepository.class);
        PaymentTransactionProcessorService service = service(
                repository,
                mock(PaymentAuditService.class),
                mock(NotificationObligationService.class)
        );
        IncomingStatusReportCommand divergent = rejected("E2E-1", "AB03");
        IncomingStatusReportCommand unauthorized = accepted("E2E-2");
        List<AuthenticatedStatusReport> input = authenticatedReports(divergent, unauthorized);
        when(repository.classifyAndApplyIncomingStatusReports(input))
                .thenReturn(new StatusReportPersistenceResult(
                        List.of(),
                        List.of(),
                        List.of(input.get(0)),
                        List.of(input.get(1))
                ));

        var result = service.processStatusReports(input);

        assertThat(result.divergentStatusReports()).containsExactly(input.get(0));
        assertThat(result.unauthorizedStatusReports()).containsExactly(input.get(1));
    }

    @Test
    void newPaymentsPersistAuditAndCombinedNotificationObligations() {
        PaymentTransactionRepository repository = mock(PaymentTransactionRepository.class);
        PaymentAuditService auditService = mock(PaymentAuditService.class);
        NotificationObligationService notificationService = mock(NotificationObligationService.class);
        PaymentTransactionProcessorService service = service(repository, auditService, notificationService);
        PaymentTransactionCommand accepted = payment("E2E-ACCEPTED");
        PaymentTransactionCommand rejected = payment("E2E-REJECTED");
        PaymentRejection rejection = PaymentRejection.insufficientFunds(rejected);
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

        verify(auditService).storeAdmissionEvents(List.of(accepted, rejected), List.of(rejection));
        verify(notificationService).storeTransactionObligations(List.of(accepted), List.of(rejection));
        verify(notificationService, never()).storeStatusObligations(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList()
        );
    }

    private PaymentTransactionProcessorService service(
            PaymentTransactionRepository repository,
            PaymentAuditService auditService,
            NotificationObligationService notificationService
    ) {
        return new PaymentTransactionProcessorService(repository, auditService, notificationService);
    }

    private static IncomingStatusReportCommand accepted(String paymentId, String... codes) {
        return report(paymentId, StatusReportOutcome.ACCEPTED, codes);
    }

    private static IncomingStatusReportCommand rejected(String paymentId, String... codes) {
        return report(paymentId, StatusReportOutcome.REJECTED, codes);
    }

    private static IncomingStatusReportCommand report(
            String paymentId,
            StatusReportOutcome outcome,
            String... codes
    ) {
        return new IncomingStatusReportCommand(
                paymentId,
                outcome,
                java.util.Arrays.stream(codes).map(StatusReasonCode::of).toList()
        );
    }

    private static List<AuthenticatedStatusReport> authenticatedReports(
            IncomingStatusReportCommand... reports
    ) {
        List<AuthenticatedStatusReport> authenticated = new ArrayList<>(reports.length);
        for (int ordinal = 0; ordinal < reports.length; ordinal++) {
            authenticated.add(new AuthenticatedStatusReport(ordinal, "20000001", reports[ordinal]));
        }
        return authenticated;
    }

    private static List<AuthenticatedPaymentRequest> authenticatedPayments(
            PaymentTransactionCommand... payments
    ) {
        List<AuthenticatedPaymentRequest> authenticated = new ArrayList<>(payments.length);
        for (int ordinal = 0; ordinal < payments.length; ordinal++) {
            PaymentTransactionCommand payment = payments[ordinal];
            authenticated.add(new AuthenticatedPaymentRequest(
                    ordinal,
                    payment.getSender().getAccount().getBankCode(),
                    payment
            ));
        }
        return authenticated;
    }

    private static PaymentTransactionCommand payment(String paymentId) {
        return PaymentTransactionCommand.builder()
                .paymentId(paymentId)
                .amountCents(1_000L)
                .sender(party("10000001"))
                .receiver(party("20000001"))
                .build();
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
