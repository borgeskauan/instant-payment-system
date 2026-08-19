package br.kauan.notificationgateway.delivery;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationDeliveryRepositoryTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(NamedParameterJdbcTemplate.class, () -> mock(NamedParameterJdbcTemplate.class))
            .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class))
            .withBean(NotificationDeliveryRepository.class);

    @Test
    void repositoryBeanUsesAutowiredConstructor() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(NotificationDeliveryRepository.class));
    }

    @Test
    void retryableFailureDoesNotRewriteAckedDeliveries() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        NotificationDeliveryRepository repository = new NotificationDeliveryRepository(
                jdbcTemplate,
                mock(TransactionTemplate.class)
        );
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        repository.markRetryableFailed("v1:abc", "send failed", Duration.ofSeconds(1));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), any(MapSqlParameterSource.class));
        assertThat(sqlCaptor.getValue()).contains("delivery_status <> 'ACKED'");
    }

    @Test
    void saveAllIfAbsentUsesOneSetBasedInsertInsideOneTransaction() throws Exception {
        NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        Array textArray = mock(Array.class);
        Array payloadArray = mock(Array.class);
        Array booleanArray = mock(Array.class);
        ResultSet rows = mock(ResultSet.class);

        when(named.getJdbcTemplate()).thenReturn(jdbc);
        when(transaction.execute(org.mockito.ArgumentMatchers
                .<TransactionCallback<List<NotificationDelivery>>>any()))
                .thenAnswer(invocation -> {
                    TransactionCallback<List<NotificationDelivery>> callback = invocation.getArgument(0);
                    return callback.doInTransaction(mock(TransactionStatus.class));
                });
        when(jdbc.execute(org.mockito.ArgumentMatchers
                .<ConnectionCallback<List<NotificationDelivery>>>any()))
                .thenAnswer(invocation -> {
                    ConnectionCallback<List<NotificationDelivery>> callback = invocation.getArgument(0);
                    return callback.doInConnection(connection);
                });
        when(connection.createArrayOf(eq("text"), any(Object[].class))).thenReturn(textArray);
        when(connection.createArrayOf(eq("bytea"), any(Object[].class))).thenReturn(payloadArray);
        when(connection.createArrayOf(eq("boolean"), any(Object[].class))).thenReturn(booleanArray);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString("communication_id")).thenReturn("v1:first");
        when(rows.getString("recipient_ispb")).thenReturn("20000001");

        NotificationDeliveryRepository repository =
                new NotificationDeliveryRepository(named, transaction, Clock.systemUTC());

        List<NotificationDelivery> directDeliveries = repository.saveAllIfAbsent(List.of(
                new IncomingNotification(
                        "v1:first",
                        "20000001",
                        "SETTLED_NOTIFICATION",
                        "E2E-1",
                        "ACSC",
                        "v1",
                        new byte[]{1, 2}
                ),
                new IncomingNotification(
                        "v1:second",
                        "20000002",
                        "REJECTED_NOTIFICATION",
                        "E2E-2",
                        "RJCT",
                        "v1",
                        new byte[]{3, 4}
                )
        ), Set.of("20000001"), Duration.ofSeconds(30));

        assertThat(directDeliveries).singleElement().satisfies(delivery -> {
            assertThat(delivery.communicationId()).isEqualTo("v1:first");
            assertThat(delivery.recipientIspb()).isEqualTo("20000001");
            assertThat(delivery.payload()).containsExactly(1, 2);
        });
        verify(transaction, times(1)).execute(any());
        verify(jdbc, times(1)).execute(org.mockito.ArgumentMatchers
                .<ConnectionCallback<List<NotificationDelivery>>>any());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue())
                .contains("FROM unnest(")
                .contains("?::text[]")
                .contains("?::bytea[]")
                .contains("?::boolean[]")
                .contains("ON CONFLICT (communication_id) DO NOTHING")
                .contains("WHERE delivery_status = 'IN_FLIGHT'");
        verify(statement, times(1)).executeQuery();
        verify(connection, times(6)).createArrayOf(eq("text"), any(Object[].class));
        verify(connection, times(1)).createArrayOf(eq("bytea"), any(Object[].class));
        verify(connection, times(1)).createArrayOf(eq("boolean"), any(Object[].class));
        verify(textArray, times(6)).free();
        verify(payloadArray).free();
        verify(booleanArray).free();
    }

    @Test
    void acknowledgeAllUsesOneAuthenticatedArrayUpdateInsideOneTransaction() throws Exception {
        NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        Array communicationIds = mock(Array.class);
        Array recipientIspbs = mock(Array.class);

        when(named.getJdbcTemplate()).thenReturn(jdbc);
        when(transaction.execute(org.mockito.ArgumentMatchers.<TransactionCallback<Integer>>any()))
                .thenAnswer(invocation -> {
                    TransactionCallback<Integer> callback = invocation.getArgument(0);
                    return callback.doInTransaction(mock(TransactionStatus.class));
                });
        when(jdbc.execute(org.mockito.ArgumentMatchers.<ConnectionCallback<Integer>>any()))
                .thenAnswer(invocation -> {
                    ConnectionCallback<Integer> callback = invocation.getArgument(0);
                    return callback.doInConnection(connection);
                });
        when(connection.createArrayOf(eq("text"), any(Object[].class)))
                .thenReturn(communicationIds, recipientIspbs);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, true, false);

        NotificationDeliveryRepository repository =
                new NotificationDeliveryRepository(named, transaction, Clock.systemUTC());

        int updated = repository.acknowledgeAll(List.of(
                new Acknowledgement("v1:first", "20000001"),
                new Acknowledgement("v1:second", "20000002")
        ));

        assertThat(updated).isEqualTo(2);
        verify(transaction, times(1)).execute(any());
        verify(jdbc, times(1)).execute(org.mockito.ArgumentMatchers.<ConnectionCallback<Integer>>any());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue())
                .contains("FROM unnest(?::text[], ?::text[])")
                .contains("delivery.communication_id = ack.communication_id")
                .contains("delivery.recipient_ispb = ack.recipient_ispb")
                .contains("delivery.delivery_status <> 'ACKED'")
                .contains("RETURNING delivery.communication_id");

        ArgumentCaptor<Object[]> arrays = ArgumentCaptor.forClass(Object[].class);
        verify(connection, times(2)).createArrayOf(eq("text"), arrays.capture());
        assertThat(arrays.getAllValues()).containsExactly(
                new String[]{"v1:first", "v1:second"},
                new String[]{"20000001", "20000002"}
        );
        verify(communicationIds).free();
        verify(recipientIspbs).free();
    }

    @Test
    void acknowledgeAllSkipsJdbcForAnEmptyBatch() {
        NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        NotificationDeliveryRepository repository =
                new NotificationDeliveryRepository(named, transaction, Clock.systemUTC());

        assertThat(repository.acknowledgeAll(List.of())).isZero();

        verifyNoInteractions(named, transaction);
    }
}
