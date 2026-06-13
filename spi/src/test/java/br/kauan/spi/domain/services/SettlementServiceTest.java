package br.kauan.spi.domain.services;

import br.kauan.spi.domain.entity.transfer.BankAccount;
import br.kauan.spi.domain.entity.transfer.BankAccountType;
import br.kauan.spi.domain.entity.transfer.Party;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;
import br.kauan.spi.port.output.FundsRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class SettlementServiceTest {

    @Test
    void makeSettlementOnlyDebitsAndCreditsProvisionedAccounts() {
        FundsRepository fundsRepository = mock(FundsRepository.class);
        SettlementService settlementService = new SettlementService(fundsRepository);

        PaymentTransaction paymentTransaction = paymentTransaction();

        settlementService.makeSettlement(paymentTransaction);

        verify(fundsRepository).deductFunds("11111111", BigDecimal.TEN);
        verify(fundsRepository).addFunds("22222222", BigDecimal.TEN);
        verifyNoMoreInteractions(fundsRepository);
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
