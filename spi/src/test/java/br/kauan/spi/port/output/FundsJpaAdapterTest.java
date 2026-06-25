package br.kauan.spi.port.output;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FundsJpaAdapterTest {

    @Test
    void provisionAccountCreatesSixteenBucketsWhenItDoesNotExist() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        FundsJpaAdapter adapter = new FundsJpaAdapter(jdbcTemplate);
        when(jdbcTemplate.queryForObject(
                org.mockito.ArgumentMatchers.contains("COUNT"),
                eq(Integer.class),
                eq("10000001")
        )).thenReturn(0);

        adapter.provisionAccount("10000001", 16000L, true);

        verify(jdbcTemplate, times(16)).update(
                org.mockito.ArgumentMatchers.contains("DO UPDATE"),
                eq("10000001"),
                any(Integer.class),
                eq(1000L)
        );
    }

    @Test
    void provisionAccountResetsExistingBucketsWhenRequested() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        FundsJpaAdapter adapter = new FundsJpaAdapter(jdbcTemplate);
        when(jdbcTemplate.queryForObject(
                org.mockito.ArgumentMatchers.contains("COUNT"),
                eq(Integer.class),
                eq("10000001")
        )).thenReturn(16);

        adapter.provisionAccount("10000001", 16000L, true);

        verify(jdbcTemplate, times(16)).update(
                org.mockito.ArgumentMatchers.contains("DO UPDATE"),
                eq("10000001"),
                any(Integer.class),
                eq(1000L)
        );
    }

    @Test
    void provisionAccountPreservesExistingBucketsWhenResetIsDisabled() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        FundsJpaAdapter adapter = new FundsJpaAdapter(jdbcTemplate);
        when(jdbcTemplate.queryForObject(
                org.mockito.ArgumentMatchers.contains("COUNT"),
                eq(Integer.class),
                eq("10000001")
        )).thenReturn(16);

        adapter.provisionAccount("10000001", 1000L, false);

        verify(jdbcTemplate, times(16)).update(
                org.mockito.ArgumentMatchers.contains("DO NOTHING"),
                eq("10000001"),
                any(Integer.class),
                any(Long.class)
        );
    }

    @Test
    void getAvailableFundsReturnsSumOfBuckets() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        FundsJpaAdapter adapter = new FundsJpaAdapter(jdbcTemplate);
        when(jdbcTemplate.queryForObject(
                org.mockito.ArgumentMatchers.contains("COUNT"),
                eq(Integer.class),
                eq("10000001")
        )).thenReturn(16);
        when(jdbcTemplate.queryForObject(
                org.mockito.ArgumentMatchers.contains("SUM"),
                eq(Long.class),
                eq("10000001")
        )).thenReturn(1000L);

        long balance = adapter.getAvailableFundsCents("10000001");

        assertEquals(1000L, balance);
    }

    @Test
    void getAvailableFundsFailsWhenAccountDoesNotExist() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        FundsJpaAdapter adapter = new FundsJpaAdapter(jdbcTemplate);
        when(jdbcTemplate.queryForObject(
                org.mockito.ArgumentMatchers.contains("COUNT"),
                eq(Integer.class),
                eq("10000001")
        )).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> adapter.getAvailableFundsCents("10000001"));
    }
}
