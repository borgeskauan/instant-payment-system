package br.kauan.spi.domain.services;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.status.StatusBatch;
import br.kauan.spi.domain.entity.status.StatusReport;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.domain.services.notification.NotificationService;
import br.kauan.spi.port.output.PaymentTransactionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentTransactionProcessorServiceTest {

    @Test
    void acceptedStatusUpdatesOnlyThePaymentStatusForAuditMilestones() {
        PaymentTransactionRepository paymentTransactionRepository = mock(PaymentTransactionRepository.class);
        NotificationService notificationService = mock(NotificationService.class);
        SettlementService settlementService = mock(SettlementService.class);
        PaymentTransactionProcessorService service = new PaymentTransactionProcessorService(
                paymentTransactionRepository,
                notificationService,
                settlementService
        );
        PaymentTransaction paymentTransaction = paymentTransaction();
        when(paymentTransactionRepository.findById("E2E-1")).thenReturn(Optional.of(paymentTransaction));

        service.processStatusBatch("00000000", StatusBatch.builder()
                .statusReports(List.of(StatusReport.builder()
                        .originalPaymentId("E2E-1")
                        .status(PaymentStatus.ACCEPTED_IN_PROCESS)
                        .build()))
                .build());

        verify(paymentTransactionRepository).updateStatus("E2E-1", PaymentStatus.ACCEPTED_IN_PROCESS);
        verify(settlementService).makeSettlement(paymentTransaction);
        verify(notificationService).sendConfirmationNotification(paymentTransaction);
        verify(paymentTransactionRepository).updateStatus("E2E-1", PaymentStatus.ACCEPTED_AND_SETTLED);
        verify(paymentTransactionRepository, never()).saveTransaction(paymentTransaction, PaymentStatus.ACCEPTED_IN_PROCESS);
        verify(paymentTransactionRepository, never()).saveTransaction(paymentTransaction, PaymentStatus.ACCEPTED_AND_SETTLED);
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
