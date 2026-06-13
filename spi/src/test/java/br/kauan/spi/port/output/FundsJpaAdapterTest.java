package br.kauan.spi.port.output;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FundsJpaAdapterTest {

    @Test
    void provisionAccountCreatesAccountWhenItDoesNotExist() {
        FundsJpaClient fundsJpaClient = mock(FundsJpaClient.class);
        FundsJpaAdapter adapter = new FundsJpaAdapter(fundsJpaClient);
        when(fundsJpaClient.findById("10000001")).thenReturn(Optional.empty());

        adapter.provisionAccount("10000001", BigDecimal.TEN, true);

        verify(fundsJpaClient).save(fundsEntity("10000001", BigDecimal.TEN));
    }

    @Test
    void provisionAccountResetsExistingAccountWhenRequested() {
        FundsJpaClient fundsJpaClient = mock(FundsJpaClient.class);
        FundsJpaAdapter adapter = new FundsJpaAdapter(fundsJpaClient);
        FundsEntity entity = fundsEntity("10000001", BigDecimal.ONE);
        when(fundsJpaClient.findById("10000001")).thenReturn(Optional.of(entity));

        adapter.provisionAccount("10000001", BigDecimal.TEN, true);

        verify(fundsJpaClient).save(fundsEntity("10000001", BigDecimal.TEN));
    }

    @Test
    void provisionAccountPreservesExistingAccountWhenResetIsDisabled() {
        FundsJpaClient fundsJpaClient = mock(FundsJpaClient.class);
        FundsJpaAdapter adapter = new FundsJpaAdapter(fundsJpaClient);
        FundsEntity entity = fundsEntity("10000001", BigDecimal.ONE);
        when(fundsJpaClient.findById("10000001")).thenReturn(Optional.of(entity));

        adapter.provisionAccount("10000001", BigDecimal.TEN, false);

        verify(fundsJpaClient, never()).save(entity);
    }

    @Test
    void getAvailableFundsReturnsExistingAccountBalance() {
        FundsJpaClient fundsJpaClient = mock(FundsJpaClient.class);
        FundsJpaAdapter adapter = new FundsJpaAdapter(fundsJpaClient);
        when(fundsJpaClient.findById("10000001")).thenReturn(Optional.of(fundsEntity("10000001", BigDecimal.TEN)));

        BigDecimal balance = adapter.getAvailableFunds("10000001");

        assertEquals(BigDecimal.TEN, balance);
    }

    @Test
    void getAvailableFundsFailsWhenAccountDoesNotExist() {
        FundsJpaClient fundsJpaClient = mock(FundsJpaClient.class);
        FundsJpaAdapter adapter = new FundsJpaAdapter(fundsJpaClient);
        when(fundsJpaClient.findById("10000001")).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> adapter.getAvailableFunds("10000001"));
        verify(fundsJpaClient, never()).save(fundsEntity("10000001", BigDecimal.ZERO));
    }

    private static FundsEntity fundsEntity(String bankCode, BigDecimal balance) {
        FundsEntity entity = new FundsEntity();
        entity.setBankCode(bankCode);
        entity.setBalance(balance);
        return entity;
    }
}
