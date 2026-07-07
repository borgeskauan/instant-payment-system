package br.kauan.spi.domain.services;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.domain.services.notification.NotificationService;
import br.kauan.spi.domain.services.tracing.SpiTraceEvent;
import br.kauan.spi.domain.services.tracing.SpiTraceRecorder;
import br.kauan.spi.port.output.PaymentTransactionPersistenceResult;
import br.kauan.spi.port.output.PaymentTransactionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentTransactionProcessorServiceTest {

    @Test
    void acceptedStatusSettlesAndSendsConfirmationWhenSettlementSucceeds() {
        PaymentTransactionRepository paymentTransactionRepository = mock(PaymentTransactionRepository.class);
        NotificationService notificationService = mock(NotificationService.class);
        SettlementService settlementService = mock(SettlementService.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        PaymentTransactionProcessorService service = new PaymentTransactionProcessorService(
                paymentTransactionRepository,
                notificationService,
                settlementService,
                traceRecorder
        );
        PaymentTransactionCommand paymentTransaction = paymentTransaction();
        when(settlementService.tryMakeSettlements(List.of("E2E-1")))
                .thenReturn(new SettlementResult(List.of(paymentTransaction), List.of()));

        service.processStatusReports(List.of(StatusReportCommand.builder()
                        .originalPaymentId("E2E-1")
                        .status(PaymentStatus.ACCEPTED_IN_PROCESS)
                .build()));

        verify(settlementService).tryMakeSettlements(List.of("E2E-1"));
        verify(notificationService).sendConfirmationNotifications(List.of(paymentTransaction));
        verify(traceRecorder).record("E2E-1", SpiTraceEvent.SETTLEMENT_COMPLETED);
        verify(traceRecorder).record("E2E-1", SpiTraceEvent.CONFIRMATION_NOTIFICATION_ENQUEUED);
        verify(paymentTransactionRepository, never()).findById("E2E-1");
        verify(paymentTransactionRepository, never()).updateStatuses(List.of("E2E-1"), PaymentStatus.ACCEPTED_IN_PROCESS);
        verify(paymentTransactionRepository, never()).updateStatuses(List.of("E2E-1"), PaymentStatus.ACCEPTED_AND_SETTLED);
    }

    @Test
    void acceptedStatusesFromSamePollAreSettledTogether() {
        PaymentTransactionRepository paymentTransactionRepository = mock(PaymentTransactionRepository.class);
        NotificationService notificationService = mock(NotificationService.class);
        SettlementService settlementService = mock(SettlementService.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        PaymentTransactionProcessorService service = new PaymentTransactionProcessorService(
                paymentTransactionRepository,
                notificationService,
                settlementService,
                traceRecorder
        );
        PaymentTransactionCommand firstPayment = paymentTransaction("E2E-1");
        PaymentTransactionCommand secondPayment = paymentTransaction("E2E-2");
        when(settlementService.tryMakeSettlements(List.of("E2E-1", "E2E-2")))
                .thenReturn(new SettlementResult(List.of(firstPayment, secondPayment), List.of()));

        service.processStatusReports(List.of(
                        StatusReportCommand.builder()
                                .originalPaymentId("E2E-1")
                                .status(PaymentStatus.ACCEPTED_IN_PROCESS)
                                .build(),
                        StatusReportCommand.builder()
                                .originalPaymentId("E2E-2")
                                .status(PaymentStatus.ACCEPTED_IN_PROCESS)
                                .build()));

        verify(settlementService).tryMakeSettlements(List.of("E2E-1", "E2E-2"));
        verify(notificationService).sendConfirmationNotifications(List.of(firstPayment, secondPayment));
        verify(traceRecorder).record("E2E-1", SpiTraceEvent.SETTLEMENT_COMPLETED);
        verify(traceRecorder).record("E2E-1", SpiTraceEvent.CONFIRMATION_NOTIFICATION_ENQUEUED);
        verify(traceRecorder).record("E2E-2", SpiTraceEvent.SETTLEMENT_COMPLETED);
        verify(traceRecorder).record("E2E-2", SpiTraceEvent.CONFIRMATION_NOTIFICATION_ENQUEUED);
        verify(paymentTransactionRepository, never()).updateStatuses(
                List.of("E2E-1", "E2E-2"),
                PaymentStatus.ACCEPTED_IN_PROCESS
        );
    }

    @Test
    void acceptedStatusLeavesAcceptedInProcessAndSkipsConfirmationWhenSettlementFails() {
        PaymentTransactionRepository paymentTransactionRepository = mock(PaymentTransactionRepository.class);
        NotificationService notificationService = mock(NotificationService.class);
        SettlementService settlementService = mock(SettlementService.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        PaymentTransactionProcessorService service = new PaymentTransactionProcessorService(
                paymentTransactionRepository,
                notificationService,
                settlementService,
                traceRecorder
        );
        when(settlementService.tryMakeSettlements(List.of("E2E-1")))
                .thenReturn(new SettlementResult(List.of(), List.of("E2E-1")));

        service.processStatusReports(List.of(StatusReportCommand.builder()
                        .originalPaymentId("E2E-1")
                        .status(PaymentStatus.ACCEPTED_IN_PROCESS)
                        .build()));

        verify(settlementService).tryMakeSettlements(List.of("E2E-1"));
        verify(paymentTransactionRepository, never()).findById("E2E-1");
        verify(paymentTransactionRepository).updateStatuses(List.of("E2E-1"), PaymentStatus.ACCEPTED_IN_PROCESS);
        verifyNoInteractions(notificationService);
        verify(paymentTransactionRepository, never()).updateStatuses(List.of("E2E-1"), PaymentStatus.ACCEPTED_AND_SETTLED);
    }

    @Test
    void rejectedStatusesUpdatePaymentsInOneBatchBeforeSendingNotifications() {
        PaymentTransactionRepository paymentTransactionRepository = mock(PaymentTransactionRepository.class);
        NotificationService notificationService = mock(NotificationService.class);
        SettlementService settlementService = mock(SettlementService.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        PaymentTransactionProcessorService service = new PaymentTransactionProcessorService(
                paymentTransactionRepository,
                notificationService,
                settlementService,
                traceRecorder
        );
        PaymentTransactionCommand firstPayment = paymentTransaction("E2E-1");
        PaymentTransactionCommand secondPayment = paymentTransaction("E2E-2");
        when(paymentTransactionRepository.findById("E2E-1")).thenReturn(java.util.Optional.of(firstPayment));
        when(paymentTransactionRepository.findById("E2E-2")).thenReturn(java.util.Optional.of(secondPayment));

        service.processStatusReports(List.of(
                StatusReportCommand.builder()
                        .originalPaymentId("E2E-1")
                        .status(PaymentStatus.REJECTED)
                        .build(),
                StatusReportCommand.builder()
                        .originalPaymentId("E2E-2")
                        .status(PaymentStatus.REJECTED)
                        .build()
        ));

        verify(paymentTransactionRepository).updateStatuses(
                List.of("E2E-1", "E2E-2"),
                PaymentStatus.REJECTED
        );
        verify(notificationService).sendRejectionNotifications(List.of(firstPayment, secondPayment));
    }

    @Test
    void transactionRequestSavesPaymentsAndEnqueuesAcceptanceNotifications() {
        PaymentTransactionRepository paymentTransactionRepository = mock(PaymentTransactionRepository.class);
        NotificationService notificationService = mock(NotificationService.class);
        SettlementService settlementService = mock(SettlementService.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        PaymentTransactionProcessorService service = new PaymentTransactionProcessorService(
                paymentTransactionRepository,
                notificationService,
                settlementService,
                traceRecorder
        );
        PaymentTransactionCommand firstPayment = paymentTransaction("E2E-1", "10000001", "20000001");
        PaymentTransactionCommand secondPayment = paymentTransaction("E2E-2", "10000002", "20000002");
        when(paymentTransactionRepository.storeAndClassifyIncomingPaymentRequests(
                List.of(firstPayment, secondPayment)
        )).thenReturn(new PaymentTransactionPersistenceResult(
                List.of(firstPayment, secondPayment),
                List.of()
        ));

        service.processTransactions(List.of(firstPayment, secondPayment));

        verify(paymentTransactionRepository).storeAndClassifyIncomingPaymentRequests(
                List.of(firstPayment, secondPayment)
        );
        verify(traceRecorder).record("E2E-1", SpiTraceEvent.REQUEST_SAVED);
        verify(traceRecorder).record("E2E-2", SpiTraceEvent.REQUEST_SAVED);
        verify(notificationService).sendAcceptanceRequests(List.of(firstPayment, secondPayment));
        verify(traceRecorder).record("E2E-1", SpiTraceEvent.ACCEPTANCE_NOTIFICATION_ENQUEUED);
        verify(traceRecorder).record("E2E-2", SpiTraceEvent.ACCEPTANCE_NOTIFICATION_ENQUEUED);
    }

    @Test
    void transactionRequestOnlyNotifiesAcceptanceRequestsReturnedByRepository() {
        PaymentTransactionRepository paymentTransactionRepository = mock(PaymentTransactionRepository.class);
        NotificationService notificationService = mock(NotificationService.class);
        SettlementService settlementService = mock(SettlementService.class);
        SpiTraceRecorder traceRecorder = mock(SpiTraceRecorder.class);
        PaymentTransactionProcessorService service = new PaymentTransactionProcessorService(
                paymentTransactionRepository,
                notificationService,
                settlementService,
                traceRecorder
        );
        PaymentTransactionCommand waitingDuplicate = paymentTransaction("E2E-WAITING", "10000001", "20000001");
        PaymentTransactionCommand advancedDuplicate = paymentTransaction("E2E-SETTLED", "10000002", "20000002");
        PaymentTransactionCommand divergentDuplicate = paymentTransaction("E2E-DIVERGENT", "10000003", "20000003");
        when(paymentTransactionRepository.storeAndClassifyIncomingPaymentRequests(
                List.of(waitingDuplicate, advancedDuplicate, divergentDuplicate)
        )).thenReturn(new PaymentTransactionPersistenceResult(
                List.of(waitingDuplicate),
                List.of(divergentDuplicate)
        ));

        PaymentTransactionPersistenceResult result = service.processTransactions(List.of(
                waitingDuplicate,
                advancedDuplicate,
                divergentDuplicate
        ));

        verify(notificationService).sendAcceptanceRequests(List.of(waitingDuplicate));
        verify(traceRecorder).record("E2E-WAITING", SpiTraceEvent.ACCEPTANCE_NOTIFICATION_ENQUEUED);
        verify(traceRecorder, never()).record("E2E-SETTLED", SpiTraceEvent.ACCEPTANCE_NOTIFICATION_ENQUEUED);
        verify(traceRecorder, never()).record("E2E-DIVERGENT", SpiTraceEvent.ACCEPTANCE_NOTIFICATION_ENQUEUED);
        org.assertj.core.api.Assertions.assertThat(result.divergentDuplicates())
                .containsExactly(divergentDuplicate);
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
