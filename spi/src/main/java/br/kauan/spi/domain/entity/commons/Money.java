package br.kauan.spi.domain.entity.commons;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {

    private static final int CENTS_SCALE = 2;

    private Money() {
    }

    public static long toCents(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("Amount is required");
        }
        return amount.movePointRight(CENTS_SCALE)
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact();
    }

    public static BigDecimal toDecimal(long cents) {
        return BigDecimal.valueOf(cents, CENTS_SCALE);
    }
}
