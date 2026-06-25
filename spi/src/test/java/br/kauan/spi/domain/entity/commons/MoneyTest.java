package br.kauan.spi.domain.entity.commons;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest {

    @Test
    void convertsDecimalAmountToCentsExactly() {
        assertEquals(12345L, Money.toCents(new BigDecimal("123.45")));
        assertEquals(1000L, Money.toCents(BigDecimal.TEN));
        assertEquals(1L, Money.toCents(new BigDecimal("0.01")));
    }

    @Test
    void rejectsAmountsWithMoreThanTwoDecimalPlaces() {
        assertThrows(ArithmeticException.class, () -> Money.toCents(new BigDecimal("10.001")));
    }

    @Test
    void convertsCentsToDecimalAmount() {
        assertEquals(new BigDecimal("123.45"), Money.toDecimal(12345L));
        assertEquals(new BigDecimal("0.01"), Money.toDecimal(1L));
    }
}
