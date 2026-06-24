package br.kauan.spi.domain.services;

import br.kauan.spi.port.output.FundsRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParticipantSettlementAccountServiceTest {

    @Test
    void provisionSettlementAccountDelegatesToFundsRepository() {
        FundsRepository fundsRepository = mock(FundsRepository.class);
        ParticipantSettlementAccountService service = new ParticipantSettlementAccountService(fundsRepository);

        service.provisionSettlementAccount("10000001", BigDecimal.valueOf(1_000_000_000), true);

        verify(fundsRepository).provisionAccount("10000001", 100_000_000_000L, true);
    }

    @Test
    void provisionSettlementAccountRejectsInvalidIspb() {
        FundsRepository fundsRepository = mock(FundsRepository.class);
        ParticipantSettlementAccountService service = new ParticipantSettlementAccountService(fundsRepository);

        assertThrows(IllegalArgumentException.class,
                () -> service.provisionSettlementAccount("ABC", BigDecimal.TEN, true));
    }

    @Test
    void provisionSettlementAccountRejectsNegativeBalance() {
        FundsRepository fundsRepository = mock(FundsRepository.class);
        ParticipantSettlementAccountService service = new ParticipantSettlementAccountService(fundsRepository);

        assertThrows(IllegalArgumentException.class,
                () -> service.provisionSettlementAccount("10000001", BigDecimal.valueOf(-1), true));
    }

    @Test
    void provisionSettlementAccountRejectsBalanceWithMoreThanTwoDecimalPlaces() {
        FundsRepository fundsRepository = mock(FundsRepository.class);
        ParticipantSettlementAccountService service = new ParticipantSettlementAccountService(fundsRepository);

        assertThrows(ArithmeticException.class,
                () -> service.provisionSettlementAccount("10000001", new BigDecimal("10.001"), true));
    }

    @Test
    void getSettlementAccountBalanceReturnsRepositoryBalance() {
        FundsRepository fundsRepository = mock(FundsRepository.class);
        ParticipantSettlementAccountService service = new ParticipantSettlementAccountService(fundsRepository);
        when(fundsRepository.getAvailableFundsCents("10000001")).thenReturn(1000L);

        BigDecimal balance = service.getSettlementAccountBalance("10000001");

        assertEquals(new BigDecimal("10.00"), balance);
        verify(fundsRepository).getAvailableFundsCents("10000001");
    }

    @Test
    void getSettlementAccountBalanceRejectsInvalidIspb() {
        FundsRepository fundsRepository = mock(FundsRepository.class);
        ParticipantSettlementAccountService service = new ParticipantSettlementAccountService(fundsRepository);

        assertThrows(IllegalArgumentException.class,
                () -> service.getSettlementAccountBalance("ABC"));
    }
}
