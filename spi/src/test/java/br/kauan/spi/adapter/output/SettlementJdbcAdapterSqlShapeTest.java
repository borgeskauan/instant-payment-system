package br.kauan.spi.adapter.output;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementJdbcAdapterSqlShapeTest {

    @Test
    void settleAcceptedPaymentsUsesStableArraySqlForBatchInputs() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SettlementJdbcAdapter adapter = new SettlementJdbcAdapter(
                jdbcTemplate,
                new PaymentTransactionRepositoryMapper()
        );
        when(jdbcTemplate.execute(any(ConnectionCallback.class)))
                .thenThrow(new EmptyResultDataAccessException(1));

        try {
            adapter.settleAcceptedPayments(
                    java.util.List.of("E2E-1", "E2E-2", "E2E-3"),
                    PaymentStatus.WAITING_ACCEPTANCE,
                    PaymentStatus.ACCEPTED_AND_SETTLED
            );
        } catch (EmptyResultDataAccessException ignored) {
            // The mocked callback stops execution after proving the stable JDBC path is used.
        }

        verify(jdbcTemplate).execute(any(ConnectionCallback.class));
        verify(jdbcTemplate, org.mockito.Mockito.never()).query(
                org.mockito.ArgumentMatchers.contains("IN (?, ?, ?)"),
                any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class)
        );
        verify(jdbcTemplate, org.mockito.Mockito.never()).query(
                org.mockito.ArgumentMatchers.contains("(?, ?)"),
                any(org.springframework.jdbc.core.ResultSetExtractor.class),
                any(Object[].class)
        );
        verify(jdbcTemplate, org.mockito.Mockito.never()).update(anyString(), any(Object[].class));
    }
}
