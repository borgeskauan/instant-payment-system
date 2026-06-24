package br.kauan.spi.domain.services;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.StatusBatch;
import br.kauan.spi.domain.entity.status.StatusReport;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentBatch;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.domain.services.notification.NotificationService;
import br.kauan.spi.domain.services.tracing.SpiTraceEvent;
import br.kauan.spi.domain.services.tracing.SpiTraceRecorder;
import br.kauan.spi.port.output.PaymentTransactionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentTransactionProcessorServiceTest {

    @Test
    void acceptedStatusUsesBatchSettlementAndSendsConfirmationWhenSettlementSucceeds() {
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
        PaymentTransaction paymentTransaction = paymentTransaction();
        when(settlementService.tryMakeSettlements(List.of("E2E-1"))).thenReturn(List.of(paymentTransaction));

        service.processStatusBatch(StatusBatch.builder()
                .statusReports(List.of(StatusReport.builder()
                        .originalPaymentId("E2E-1")
                        .status(PaymentStatus.ACCEPTED_IN_PROCESS)
                .build()))
                .build());

        verify(settlementService).tryMakeSettlements(List.of("E2E-1"));
        verify(settlementService, never()).tryMakeSettlement("E2E-1");
        verify(notificationService).sendConfirmationNotification(paymentTransaction);
        verify(traceRecorder).record("E2E-1", SpiTraceEvent.SETTLEMENT_COMPLETED);
        verify(traceRecorder).record("E2E-1", SpiTraceEvent.CONFIRMATION_NOTIFICATION_ENQUEUED);
        verify(paymentTransactionRepository, never()).findById("E2E-1");
        verify(paymentTransactionRepository, never()).updateStatus("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS);
        verify(paymentTransactionRepository, never()).updateStatus("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED);
        verify(paymentTransactionRepository, never()).saveTransaction(paymentTransaction, PaymentStatus.ACCEPTED_IN_PROCESS);
        verify(paymentTransactionRepository, never()).saveTransaction(paymentTransaction, PaymentStatus.ACCEPTED_AND_SETTLED);
    }

    @Test
    void acceptedStatusesFromSameBatchAreSettledInOneRepositoryBatch() {
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
        PaymentTransaction firstPayment = paymentTransaction("E2E-1");
        PaymentTransaction secondPayment = paymentTransaction("E2E-2");
        when(settlementService.tryMakeSettlements(List.of("E2E-1", "E2E-2")))
                .thenReturn(List.of(firstPayment, secondPayment));

        service.processStatusBatch(StatusBatch.builder()
                .statusReports(List.of(
                        StatusReport.builder()
                                .originalPaymentId("E2E-1")
                                .status(PaymentStatus.ACCEPTED_IN_PROCESS)
                                .build(),
                        StatusReport.builder()
                                .originalPaymentId("E2E-2")
                                .status(PaymentStatus.ACCEPTED_IN_PROCESS)
                                .build()))
                .build());

        verify(settlementService).tryMakeSettlements(List.of("E2E-1", "E2E-2"));
        verify(settlementService, never()).tryMakeSettlement("E2E-1");
        verify(settlementService, never()).tryMakeSettlement("E2E-2");
        verify(notificationService).sendConfirmationNotification(firstPayment);
        verify(notificationService).sendConfirmationNotification(secondPayment);
        verify(traceRecorder).record("E2E-1", SpiTraceEvent.SETTLEMENT_COMPLETED);
        verify(traceRecorder).record("E2E-1", SpiTraceEvent.CONFIRMATION_NOTIFICATION_ENQUEUED);
        verify(traceRecorder).record("E2E-2", SpiTraceEvent.SETTLEMENT_COMPLETED);
        verify(traceRecorder).record("E2E-2", SpiTraceEvent.CONFIRMATION_NOTIFICATION_ENQUEUED);
        verify(paymentTransactionRepository, never()).updateStatus("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS);
        verify(paymentTransactionRepository, never()).updateStatus("E2E-2", PaymentStatus.ACCEPTED_IN_PROCESS);
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
        when(settlementService.tryMakeSettlements(List.of("E2E-1"))).thenReturn(List.of());

        service.processStatusBatch(StatusBatch.builder()
                .statusReports(List.of(StatusReport.builder()
                        .originalPaymentId("E2E-1")
                        .status(PaymentStatus.ACCEPTED_IN_PROCESS)
                        .build()))
                .build());

        verify(settlementService).tryMakeSettlements(List.of("E2E-1"));
        verify(settlementService, never()).tryMakeSettlement("E2E-1");
        verify(paymentTransactionRepository, never()).findById("E2E-1");
        verify(paymentTransactionRepository).updateStatus("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS);
        verifyNoInteractions(notificationService);
        verify(paymentTransactionRepository, never()).updateStatus("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED);
    }

    @Test
    void transactionRequestSavesPaymentsInBatchAndEnqueuesAcceptanceNotifications() {
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
        PaymentTransaction firstPayment = paymentTransaction("E2E-1", "10000001", "20000001");
        PaymentTransaction secondPayment = paymentTransaction("E2E-2", "10000002", "20000002");

        service.processTransactionBatch(PaymentBatch.builder()
                .transactions(List.of(firstPayment, secondPayment))
                .build());

        verify(paymentTransactionRepository).saveTransactions(
                List.of(firstPayment, secondPayment),
                PaymentStatus.WAITING_ACCEPTANCE
        );
        verify(paymentTransactionRepository, never())
                .saveTransaction(any(PaymentTransaction.class), any(PaymentStatus.class));
        verify(traceRecorder).record("E2E-1", SpiTraceEvent.REQUEST_SAVED);
        verify(traceRecorder).record("E2E-2", SpiTraceEvent.REQUEST_SAVED);
        verify(notificationService).sendAcceptanceRequest("20000001", firstPayment);
        verify(notificationService).sendAcceptanceRequest("20000002", secondPayment);
        verify(traceRecorder).record("E2E-1", SpiTraceEvent.ACCEPTANCE_NOTIFICATION_ENQUEUED);
        verify(traceRecorder).record("E2E-2", SpiTraceEvent.ACCEPTANCE_NOTIFICATION_ENQUEUED);
    }

    private static PaymentTransaction paymentTransaction() {
        return paymentTransaction("E2E-1");
    }

    private static PaymentTransaction paymentTransaction(String paymentId) {
        return paymentTransaction(paymentId, "10000001", "20000001");
    }

    private static PaymentTransaction paymentTransaction(String paymentId, String senderBankCode, String receiverBankCode) {
        return PaymentTransaction.builder()
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
                        .number(1L)
                        .branch(1)
                        .type(BankAccountType.CHECKING)
                        .build())
                .build();
    }
}
