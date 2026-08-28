package br.kauan.spi.adapter.output.funds;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcFundsRepositoryTest {

    @Test
    void provisionAccountCreatesOrResetsOneParticipantBalance() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcFundsRepository adapter = new JdbcFundsRepository(jdbcTemplate);

        adapter.provisionAccount("10000001", 16_000L, true);

        verify(jdbcTemplate).update(
                contains("DO UPDATE"),
                eq("10000001"),
                eq(16_000L)
        );
    }

    @Test
    void provisionAccountPreservesOneParticipantBalanceWhenResetIsDisabled() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcFundsRepository adapter = new JdbcFundsRepository(jdbcTemplate);

        adapter.provisionAccount("10000001", 16_000L, false);

        verify(jdbcTemplate).update(
                contains("DO NOTHING"),
                eq("10000001"),
                eq(16_000L)
        );
    }

    @Test
    void getAvailableFundsReturnsParticipantBalance() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcFundsRepository adapter = new JdbcFundsRepository(jdbcTemplate);
        when(jdbcTemplate.queryForObject(
                contains("participant_balance_entity"),
                eq(Long.class),
                eq("10000001")
        )).thenReturn(1_000L);

        assertEquals(1_000L, adapter.getAvailableFundsCents("10000001"));
    }

    @Test
    void getAvailableFundsFailsWhenParticipantBalanceDoesNotExist() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcFundsRepository adapter = new JdbcFundsRepository(jdbcTemplate);
        when(jdbcTemplate.queryForObject(
                contains("participant_balance_entity"),
                eq(Long.class),
                eq("10000001")
        )).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> adapter.getAvailableFundsCents("10000001"));
    }
}
