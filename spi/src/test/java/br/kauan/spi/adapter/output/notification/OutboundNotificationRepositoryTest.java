package br.kauan.spi.adapter.output.notification;

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

class OutboundNotificationRepositoryTest {

    @Test
    void insertAllUsesOnlyImmutableNotificationColumns() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        Array textArray = mock(Array.class);
        Array payloadArray = mock(Array.class);
        NotificationPublication notification = notification("E2E-IMMUTABLE");

        when(jdbcTemplate.execute(org.mockito.ArgumentMatchers
                .<ConnectionCallback<Void>>any()))
                .thenAnswer(invocation -> {
                    ConnectionCallback<Void> callback = invocation.getArgument(0);
                    return callback.doInConnection(connection);
                });
        when(connection.createArrayOf(eq("text"), any(Object[].class))).thenReturn(textArray);
        when(connection.createArrayOf(eq("bytea"), any(Object[].class))).thenReturn(payloadArray);
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        when(statement.executeUpdate()).thenReturn(1);

        int inserted = new OutboundNotificationRepository(jdbcTemplate).insertAll(List.of(notification));

        assertThat(inserted).isOne();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue())
                .contains("INSERT INTO outbound_notification")
                .contains("FROM unnest(")
                .contains("ON CONFLICT (communication_id) DO NOTHING")
                .doesNotContain("RETURNING")
                .doesNotContain("publication_status")
                .doesNotContain("attempt_count")
                .doesNotContain("next_attempt_at")
                .doesNotContain("last_error")
                .doesNotContain("published_at")
                .doesNotContain("updated_at");
        verify(connection, times(6)).createArrayOf(eq("text"), any(Object[].class));
        verify(connection).createArrayOf(eq("bytea"), any(Object[].class));
        verify(statement).executeUpdate();
    }

    private NotificationPublication notification(String paymentId) {
        return NotificationPublication.create(
                "20000001",
                paymentId.getBytes(StandardCharsets.UTF_8),
                "ACCEPTANCE_REQUEST",
                paymentId,
                null
        );
    }
}
