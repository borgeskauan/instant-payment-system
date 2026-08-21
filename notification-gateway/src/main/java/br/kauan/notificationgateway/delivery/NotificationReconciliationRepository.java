package br.kauan.notificationgateway.delivery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class NotificationReconciliationRepository {

    private static final String FIND_UNINDEXED_AFTER_SQL = """
            SELECT
                outbox.communication_id,
                outbox.recipient_ispb,
                outbox.event_type,
                outbox.payment_id,
                outbox.notification_status,
                outbox.schema_version,
                outbox.payload
            FROM notification_outbox AS outbox
            WHERE outbox.communication_id > ?
              AND outbox.created_at <= CURRENT_TIMESTAMP - INTERVAL '1 minute'
              AND NOT EXISTS (
                  SELECT 1
                  FROM delivery_index AS index
                  WHERE index.communication_id = outbox.communication_id
              )
            ORDER BY outbox.communication_id
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
