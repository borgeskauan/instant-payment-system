package br.kauan.spi.domain.services;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.port.output.SettlementRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SettlementServiceTest {

    @Test
    void settlementServiceDoesNotExposeSinglePaymentSettlement() {
        assertThrows(NoSuchMethodException.class,
                () -> SettlementService.class.getMethod("tryMakeSettlement", String.class));
    }

    @Test
    void tryMakeSettlementsReturnsSettledOrAlreadySettledPaymentsAndNotSettledPaymentIds() {
        SettlementRepository settlementRepository = mock(SettlementRepository.class);
        SettlementService settlementService = new SettlementService(settlementRepository);

        PaymentTransactionCommand settledOrAlreadySettledPayment = paymentTransaction("E2E-1");
        when(settlementRepository.settleAcceptedPaymentsIdempotently(
                List.of("E2E-1", "E2E-2", "E2E-3"),
                PaymentStatus.WAITING_ACCEPTANCE,
                PaymentStatus.ACCEPTED_AND_SETTLED
        )).thenReturn(List.of(settledOrAlreadySettledPayment));

        SettlementResult result = settlementService.tryMakeSettlements(List.of("E2E-1", "E2E-2", "E2E-3"));

        assertEquals(List.of(settledOrAlreadySettledPayment), result.settledOrAlreadySettledPayments());
        assertEquals(List.of("E2E-2", "E2E-3"), result.notSettledPaymentIds());
    }

    @Test
    void tryMakeSettlementsDeduplicatesNotSettledPaymentIdsLikeTheRepositoryQuery() {
        SettlementRepository settlementRepository = mock(SettlementRepository.class);
        SettlementService settlementService = new SettlementService(settlementRepository);

        when(settlementRepository.settleAcceptedPaymentsIdempotently(
                List.of("E2E-1", "E2E-1", "E2E-2"),
                PaymentStatus.WAITING_ACCEPTANCE,
                PaymentStatus.ACCEPTED_AND_SETTLED
        )).thenReturn(List.of());

        SettlementResult result = settlementService.tryMakeSettlements(List.of("E2E-1", "E2E-1", "E2E-2"));

        assertEquals(List.of("E2E-1", "E2E-2"), result.notSettledPaymentIds());
    }

    private static PaymentTransactionCommand paymentTransaction() {
        return paymentTransaction("E2E-1");
    }

    private static PaymentTransactionCommand paymentTransaction(String paymentId) {
        return PaymentTransactionCommand.builder()
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
                        .number("1")
                        .branch("1")
                        .type(BankAccountType.CHECKING)
                        .build())
                .build();
    }
}
