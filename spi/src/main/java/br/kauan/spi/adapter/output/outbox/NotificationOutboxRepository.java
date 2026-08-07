package br.kauan.spi.adapter.output.outbox;

import br.kauan.spi.adapter.output.kafka.NotificationPublication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Repository
public class NotificationOutboxRepository {

    private static final int MAX_ERROR_LENGTH = 1_000;

    private static final String FIND_PENDING_SQL = """
            SELECT
                communication_id,
                recipient_ispb,
                event_type,
                payment_id,
                notification_status,
                schema_version,
                payload
            FROM notification_outbox
            WHERE publication_status = 'PENDING'
              AND next_attempt_at <= CURRENT_TIMESTAMP
            ORDER BY next_attempt_at, communication_id
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public NotificationOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertAll(List<NotificationPublication> notifications) {
        if (notifications.isEmpty()) {
            return;
        }

        StringBuilder sql = new StringBuilder("""
                INSERT INTO notification_outbox (
                    communication_id,
                    recipient_ispb,
                    event_type,
                    payment_id,
                    notification_status,
                    schema_version,
                    payload,
                    publication_status,
                    attempt_count,
                    next_attempt_at,
                    created_at,
                    updated_at
                ) VALUES
                """);
        List<Object> parameters = new ArrayList<>(notifications.size() * 7);
        for (int index = 0; index < notifications.size(); index++) {
            if (index > 0) {
                sql.append(",\n");
            }
            sql.append("(?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            NotificationPublication notification = notifications.get(index);
            parameters.add(notification.communicationId());
            parameters.add(notification.recipientIspb());
            parameters.add(notification.eventType());
            parameters.add(notification.paymentId());
            parameters.add(notification.status());
            parameters.add(notification.schemaVersion());
            parameters.add(notification.payload());
        }
        sql.append("\nON CONFLICT (communication_id) DO NOTHING");

        jdbcTemplate.update(sql.toString(), parameters.toArray());
    }

    public List<NotificationPublication> findPending(int limit) {
        if (limit <= 0) {
            return List.of();
        }

        return jdbcTemplate.query(
                FIND_PENDING_SQL,
                (resultSet, rowNumber) -> new NotificationPublication(
                        resultSet.getString("recipient_ispb"),
                        resultSet.getBytes("payload"),
                        resultSet.getString("communication_id"),
                        resultSet.getString("event_type"),
                        resultSet.getString("payment_id"),
                        resultSet.getString("notification_status"),
                        resultSet.getString("schema_version")
                ),
                limit
        );
    }

    public int markPublished(List<String> communicationIds) {
        if (communicationIds.isEmpty()) {
            return 0;
        }

        String sql = """
                UPDATE notification_outbox
                SET publication_status = 'PUBLISHED',
                    attempt_count = attempt_count + 1,
                    published_at = CURRENT_TIMESTAMP,
                    last_error = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE publication_status = 'PENDING'
                  AND communication_id IN (%s)
                """.formatted(placeholders(communicationIds.size()));
        List<Object> parameters = new ArrayList<>(communicationIds.size());
        parameters.addAll(communicationIds);
        return jdbcTemplate.update(sql, parameters.toArray());
    }

    public int scheduleRetry(List<NotificationPublicationFailure> failures, Duration retryDelay) {
        if (failures.isEmpty()) {
            return 0;
        }

        StringBuilder values = new StringBuilder();
        List<Object> parameters = new ArrayList<>(failures.size() * 2 + 1);
        parameters.add(retryDelay.toMillis());
        for (int index = 0; index < failures.size(); index++) {
            if (index > 0) {
                values.append(", ");
            }
            values.append("(?, ?)");
            NotificationPublicationFailure failure = failures.get(index);
            parameters.add(failure.communicationId());
            parameters.add(truncate(failure.error()));
        }

        String sql = """
                UPDATE notification_outbox AS outbox
                SET attempt_count = outbox.attempt_count + 1,
                    next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    last_error = failure.last_error,
                    updated_at = CURRENT_TIMESTAMP
                FROM (VALUES %s) AS failure(communication_id, last_error)
                WHERE outbox.communication_id = failure.communication_id
                  AND outbox.publication_status = 'PENDING'
                """.formatted(values);
        return jdbcTemplate.update(sql, parameters.toArray());
    }

    private String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }
}
