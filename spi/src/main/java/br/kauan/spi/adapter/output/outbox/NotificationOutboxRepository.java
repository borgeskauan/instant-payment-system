package br.kauan.spi.adapter.output.outbox;

import br.kauan.spi.adapter.output.kafka.NotificationPublication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Repository
public class NotificationOutboxRepository {

    private static final int MAX_ERROR_LENGTH = 1_000;

    private static final String INSERT_ALL_SQL = """
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
            )
            SELECT
                incoming.communication_id,
                incoming.recipient_ispb,
                incoming.event_type,
                incoming.payment_id,
                incoming.notification_status,
                incoming.schema_version,
                incoming.payload,
                'PENDING',
                0,
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP
            FROM unnest(
                ?::text[],
                ?::text[],
                ?::text[],
                ?::text[],
                ?::text[],
                ?::text[],
                ?::bytea[]
            ) AS incoming(
                communication_id,
                recipient_ispb,
                event_type,
                payment_id,
                notification_status,
                schema_version,
                payload
            )
            ON CONFLICT (communication_id) DO NOTHING
            """;

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

        jdbcTemplate.execute((ConnectionCallback<Integer>) connection -> {
            String[] communicationIds = new String[notifications.size()];
            String[] recipientIspbs = new String[notifications.size()];
            String[] eventTypes = new String[notifications.size()];
            String[] paymentIds = new String[notifications.size()];
            String[] notificationStatuses = new String[notifications.size()];
            String[] schemaVersions = new String[notifications.size()];
            byte[][] payloads = new byte[notifications.size()][];
            for (int index = 0; index < notifications.size(); index++) {
                NotificationPublication notification = notifications.get(index);
                communicationIds[index] = notification.communicationId();
                recipientIspbs[index] = notification.recipientIspb();
                eventTypes[index] = notification.eventType();
                paymentIds[index] = notification.paymentId();
                notificationStatuses[index] = notification.status();
                schemaVersions[index] = notification.schemaVersion();
                payloads[index] = notification.payload();
            }

            Array communicationIdArray = null;
            Array recipientIspbArray = null;
            Array eventTypeArray = null;
            Array paymentIdArray = null;
            Array notificationStatusArray = null;
            Array schemaVersionArray = null;
            Array payloadArray = null;
            try {
                communicationIdArray = connection.createArrayOf("text", communicationIds);
                recipientIspbArray = connection.createArrayOf("text", recipientIspbs);
                eventTypeArray = connection.createArrayOf("text", eventTypes);
                paymentIdArray = connection.createArrayOf("text", paymentIds);
                notificationStatusArray = connection.createArrayOf("text", notificationStatuses);
                schemaVersionArray = connection.createArrayOf("text", schemaVersions);
                payloadArray = connection.createArrayOf("bytea", payloads);
                try (PreparedStatement statement = connection.prepareStatement(INSERT_ALL_SQL)) {
                    statement.setArray(1, communicationIdArray);
                    statement.setArray(2, recipientIspbArray);
                    statement.setArray(3, eventTypeArray);
                    statement.setArray(4, paymentIdArray);
                    statement.setArray(5, notificationStatusArray);
                    statement.setArray(6, schemaVersionArray);
                    statement.setArray(7, payloadArray);
                    return statement.executeUpdate();
                }
            } finally {
                free(
                        communicationIdArray,
                        recipientIspbArray,
                        eventTypeArray,
                        paymentIdArray,
                        notificationStatusArray,
                        schemaVersionArray,
                        payloadArray
                );
            }
        });
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

    private void free(Array... arrays) throws SQLException {
        for (Array array : arrays) {
            if (array != null) {
                array.free();
            }
        }
    }

    private String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }
}
