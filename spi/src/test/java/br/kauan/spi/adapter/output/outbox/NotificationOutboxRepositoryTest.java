package br.kauan.spi.adapter.output.outbox;

import br.kauan.spi.adapter.output.kafka.NotificationPublication;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
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
    void publicationStateUpdatesUseIndependentTransactions() throws Exception {
        Transactional markPublished = NotificationOutboxRepository.class
                .getMethod("markPublished", List.class)
                .getAnnotation(Transactional.class);
        Transactional scheduleRetry = NotificationOutboxRepository.class
                .getMethod("scheduleRetry", List.class, Duration.class)
                .getAnnotation(Transactional.class);

        assertThat(markPublished).isNotNull();
        assertThat(markPublished.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(scheduleRetry).isNotNull();
        assertThat(scheduleRetry.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void insertAllUsesOneStableArrayStatementForDifferentBatchSizes() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet firstResult = mock(ResultSet.class);
        ResultSet secondResult = mock(ResultSet.class);
        Array textArray = mock(Array.class);
        Array payloadArray = mock(Array.class);

        when(jdbcTemplate.execute(org.mockito.ArgumentMatchers.<ConnectionCallback<List<NotificationPublication>>>any()))
                .thenAnswer(invocation -> {
                    ConnectionCallback<List<NotificationPublication>> callback = invocation.getArgument(0);
                    return callback.doInConnection(connection);
                });
        when(connection.createArrayOf(eq("text"), any(Object[].class))).thenReturn(textArray);
        when(connection.createArrayOf(eq("bytea"), any(Object[].class))).thenReturn(payloadArray);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(firstResult, secondResult);
        when(firstResult.next()).thenReturn(true, false);
        when(secondResult.next()).thenReturn(true, true, false);

        NotificationOutboxRepository repository = new NotificationOutboxRepository(
                jdbcTemplate,
                Duration.ofSeconds(1)
        );
        NotificationPublication first = notification("E2E-STABLE-1", "first", null);
        NotificationPublication second = notification("E2E-STABLE-2", "second", "ACSC");
        when(firstResult.getString("communication_id")).thenReturn(first.communicationId());
        when(secondResult.getString("communication_id"))
                .thenReturn(first.communicationId(), second.communicationId());

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
                .contains("RETURNING communication_id")
                .doesNotContain("VALUES");

        verify(jdbcTemplate, times(2))
                .execute(org.mockito.ArgumentMatchers.<ConnectionCallback<List<NotificationPublication>>>any());
        verify(connection, times(12)).createArrayOf(eq("text"), any(Object[].class));
        verify(connection, times(2)).createArrayOf(eq("bytea"), any(Object[].class));
        verify(statement, times(2)).executeQuery();
        verify(statement, times(2)).setLong(1, 1_000L);
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
