package br.kauan.spi.adapter.output.outbox;

import br.kauan.spi.adapter.output.kafka.NotificationPublication;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationOutboxRepositoryTest {

    @Test
    void insertAllUsesOneStableArrayStatementForDifferentBatchSizes() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        Array textArray = mock(Array.class);
        Array payloadArray = mock(Array.class);

        when(jdbcTemplate.execute(org.mockito.ArgumentMatchers.<ConnectionCallback<Integer>>any()))
                .thenAnswer(invocation -> {
                    ConnectionCallback<Integer> callback = invocation.getArgument(0);
                    return callback.doInConnection(connection);
                });
        when(connection.createArrayOf(eq("text"), any(Object[].class))).thenReturn(textArray);
        when(connection.createArrayOf(eq("bytea"), any(Object[].class))).thenReturn(payloadArray);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1, 2);

        NotificationOutboxRepository repository = new NotificationOutboxRepository(jdbcTemplate);
        NotificationPublication first = notification("E2E-STABLE-1", "first", null);
        NotificationPublication second = notification("E2E-STABLE-2", "second", "ACSC");

        repository.insertAll(List.of(first));
        repository.insertAll(List.of(first, second));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection, times(2)).prepareStatement(sql.capture());
        assertThat(sql.getAllValues()).hasSize(2).allMatch(sql.getAllValues().getFirst()::equals);
        assertThat(sql.getAllValues().getFirst())
                .contains("FROM unnest(")
                .contains("?::text[]")
                .contains("?::bytea[]")
                .contains("ON CONFLICT (communication_id) DO NOTHING")
                .doesNotContain("VALUES");

        verify(jdbcTemplate, times(2))
                .execute(org.mockito.ArgumentMatchers.<ConnectionCallback<Integer>>any());
        verify(connection, times(12)).createArrayOf(eq("text"), any(Object[].class));
        verify(connection, times(2)).createArrayOf(eq("bytea"), any(Object[].class));
        verify(statement, times(2)).executeUpdate();
        verify(textArray, times(12)).free();
        verify(payloadArray, times(2)).free();
    }

    private NotificationPublication notification(String paymentId, String payload, String status) {
        return NotificationPublication.create(
                "20000001",
                payload.getBytes(StandardCharsets.UTF_8),
                "SETTLED_NOTIFICATION",
                paymentId,
                status
        );
    }
}
