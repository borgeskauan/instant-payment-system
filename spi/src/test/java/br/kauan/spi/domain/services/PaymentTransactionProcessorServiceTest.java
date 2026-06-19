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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentTransactionProcessorServiceTest {

    @Test
    void acceptedStatusSettlesByPaymentIdAndSendsConfirmationWhenSettlementSucceeds() {
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
        when(settlementService.tryMakeSettlement("E2E-1")).thenReturn(Optional.of(paymentTransaction));

        service.processStatusBatch("00000000", StatusBatch.builder()
                .statusReports(List.of(StatusReport.builder()
                        .originalPaymentId("E2E-1")
                        .status(PaymentStatus.ACCEPTED_IN_PROCESS)
                        .build()))
                .build());

        verify(settlementService).tryMakeSettlement("E2E-1");
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
        when(settlementService.tryMakeSettlement("E2E-1")).thenReturn(Optional.empty());

        service.processStatusBatch("00000000", StatusBatch.builder()
                .statusReports(List.of(StatusReport.builder()
                        .originalPaymentId("E2E-1")
                        .status(PaymentStatus.ACCEPTED_IN_PROCESS)
                        .build()))
                .build());

        verify(settlementService).tryMakeSettlement("E2E-1");
        verify(paymentTransactionRepository, never()).findById("E2E-1");
        verify(paymentTransactionRepository).updateStatus("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS);
        verifyNoInteractions(notificationService);
        verify(paymentTransactionRepository, never()).updateStatus("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED);
    }

    @Test
    void transactionRequestSavesPaymentAndEnqueuesAcceptanceNotification() {
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

        service.processTransactionBatch("00000000", PaymentBatch.builder()
                .transactions(List.of(paymentTransaction))
                .build());

        verify(paymentTransactionRepository).saveTransaction(paymentTransaction, PaymentStatus.WAITING_ACCEPTANCE);
        verify(traceRecorder).record("E2E-1", SpiTraceEvent.REQUEST_SAVED);
        verify(notificationService).sendAcceptanceRequest("20000001", paymentTransaction);
        verify(traceRecorder).record("E2E-1", SpiTraceEvent.ACCEPTANCE_NOTIFICATION_ENQUEUED);
    }

    private static PaymentTransaction paymentTransaction() {
        return PaymentTransaction.builder()
                .paymentId("E2E-1")
                .amount(BigDecimal.TEN)
                .sender(party("10000001"))
                .receiver(party("20000001"))
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
