package br.kauan.notificationgateway.delivery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class NotificationReconciliationRepository {

    private static final String FIND_UNINDEXED_AFTER_SQL = """
            SELECT
                notification.communication_id,
                notification.recipient_ispb,
                notification.event_type,
                notification.payment_id,
                notification.notification_status,
                notification.schema_version,
                notification.payload
            FROM outbound_notification AS notification
            WHERE notification.communication_id > ?
              AND notification.created_at <= CURRENT_TIMESTAMP - INTERVAL '1 minute'
              AND NOT EXISTS (
                  SELECT 1
                  FROM delivery_index AS index
                  WHERE index.communication_id = notification.communication_id
              )
            ORDER BY notification.communication_id
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public NotificationReconciliationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<IncomingNotification> findUnindexedAfter(String communicationId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        String cursor = communicationId == null ? "" : communicationId;
        return jdbcTemplate.query(
                FIND_UNINDEXED_AFTER_SQL,
                (rows, ignored) -> new IncomingNotification(
                        rows.getString("communication_id"),
                        rows.getString("recipient_ispb"),
                        rows.getString("event_type"),
                        rows.getString("payment_id"),
                        rows.getString("notification_status"),
                        rows.getString("schema_version"),
                        rows.getBytes("payload")
                ),
                cursor,
                limit
        );
    }
}
