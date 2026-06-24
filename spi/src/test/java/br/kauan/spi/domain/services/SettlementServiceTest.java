package br.kauan.spi.domain.services;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.port.output.SettlementRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementServiceTest {

    @Test
    void tryMakeSettlementDelegatesByPaymentIdAndReturnsSettledTransaction() {
        SettlementRepository settlementRepository = mock(SettlementRepository.class);
        SettlementService settlementService = new SettlementService(settlementRepository);

        PaymentTransaction paymentTransaction = paymentTransaction();
        when(settlementRepository.settleAcceptedPayment(
                "E2E-1",
                PaymentStatus.WAITING_ACCEPTANCE,
                PaymentStatus.ACCEPTED_AND_SETTLED
        )).thenReturn(Optional.of(paymentTransaction));

        Optional<PaymentTransaction> settled = settlementService.tryMakeSettlement("E2E-1");

        assertSame(paymentTransaction, settled.orElseThrow());
        verify(settlementRepository).settleAcceptedPayment(
                "E2E-1",
                PaymentStatus.WAITING_ACCEPTANCE,
                PaymentStatus.ACCEPTED_AND_SETTLED
        );
    }

    @Test
    void tryMakeSettlementsReturnsSettledPaymentsAndNotSettledPaymentIds() {
        SettlementRepository settlementRepository = mock(SettlementRepository.class);
        SettlementService settlementService = new SettlementService(settlementRepository);

        PaymentTransaction settledPayment = paymentTransaction("E2E-1");
        when(settlementRepository.settleAcceptedPayments(
                List.of("E2E-1", "E2E-2", "E2E-3"),
                PaymentStatus.WAITING_ACCEPTANCE,
                PaymentStatus.ACCEPTED_AND_SETTLED
        )).thenReturn(List.of(settledPayment));

        SettlementBatchResult result = settlementService.tryMakeSettlements(List.of("E2E-1", "E2E-2", "E2E-3"));

        assertEquals(List.of(settledPayment), result.settledPayments());
        assertEquals(List.of("E2E-2", "E2E-3"), result.notSettledPaymentIds());
    }

    private static PaymentTransaction paymentTransaction() {
        return paymentTransaction("E2E-1");
    }

    private static PaymentTransaction paymentTransaction(String paymentId) {
        return PaymentTransaction.builder()
                .paymentId(paymentId)
                .amountCents(1000L)
                .sender(party("11111111"))
                .receiver(party("22222222"))
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
