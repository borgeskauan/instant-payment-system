package br.kauan.spi.domain.services;

import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.port.output.FundsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementServiceTest {

    @Test
    void makeSettlementUsesSenderBalanceReturnedWhenEnsuringAccountExists() {
        FundsRepository fundsRepository = mock(FundsRepository.class);
        SettlementService settlementService = new SettlementService(fundsRepository);
        ReflectionTestUtils.setField(settlementService, "defaultInitialBalance", BigDecimal.valueOf(1_000_000));

        PaymentTransaction paymentTransaction = paymentTransaction();
        when(fundsRepository.ensureAccountExistsAndGetBalance("11111111", BigDecimal.valueOf(1_000_000)))
                .thenReturn(BigDecimal.valueOf(1_000));
        when(fundsRepository.getAvailableFunds("11111111")).thenReturn(BigDecimal.valueOf(1_000));

        settlementService.makeSettlement(paymentTransaction);

        verify(fundsRepository).ensureAccountExistsAndGetBalance("11111111", BigDecimal.valueOf(1_000_000));
        verify(fundsRepository).ensureAccountExistsAndGetBalance("22222222", BigDecimal.valueOf(1_000_000));
        verify(fundsRepository, never()).getAvailableFunds("11111111");
        verify(fundsRepository, never()).getAvailableFunds("22222222");
        verify(fundsRepository).deductFunds("11111111", BigDecimal.TEN);
        verify(fundsRepository).addFunds("22222222", BigDecimal.TEN);
    }

    private static PaymentTransaction paymentTransaction() {
        return PaymentTransaction.builder()
                .paymentId("E2E-1")
                .amount(BigDecimal.TEN)
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
