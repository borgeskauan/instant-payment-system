package br.kauan.notificationgateway.delivery;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;

@Repository
public class NotificationDeliveryRepository {

    private static final String INSERT_SQL = """
            INSERT INTO notification_delivery (
                communication_id,
                recipient_ispb,
                event_type,
                payment_id,
                notification_status,
                schema_version,
                payload,
                delivery_status,
                next_attempt_at
            ) VALUES (
                :communicationId,
                :recipientIspb,
                :eventType,
                :paymentId,
                :status,
                :schemaVersion,
                :payload,
                :deliveryStatus,
                :nextAttemptAt
            )
            ON CONFLICT (communication_id) DO NOTHING
            """;

    private static final String CLAIM_SQL = """
            WITH candidates AS (
                SELECT communication_id
                FROM notification_delivery
                WHERE recipient_ispb IN (:ispbs)
                  AND delivery_status <> 'ACKED'
                  AND next_attempt_at <= :now
                  AND (
                    delivery_status IN ('PENDING', 'RETRYABLE_FAILED')
                    OR (delivery_status = 'IN_FLIGHT' AND lease_until <= :now)
                  )
                ORDER BY next_attempt_at, communication_id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            UPDATE notification_delivery delivery
            SET delivery_status = 'IN_FLIGHT',
                attempt_count = attempt_count + 1,
                last_attempt_at = :now,
                next_attempt_at = :leaseUntil,
                lease_until = :leaseUntil,
                last_error = NULL,
                updated_at = :now
            FROM candidates
            WHERE delivery.communication_id = candidates.communication_id
            RETURNING
                delivery.communication_id,
                delivery.recipient_ispb,
                delivery.payload
            """;

    private static final String ACK_ALL_SQL = """
            UPDATE notification_delivery AS delivery
            SET delivery_status = 'ACKED',
                acknowledged_at = ?,
                lease_until = NULL,
                updated_at = ?
            FROM unnest(?::text[], ?::text[])
                 AS ack(communication_id, recipient_ispb)
            WHERE delivery.communication_id = ack.communication_id
              AND delivery.recipient_ispb = ack.recipient_ispb
              AND delivery.delivery_status <> 'ACKED'
            RETURNING delivery.communication_id
            """;

    private static final String RETRYABLE_FAILED_SQL = """
            UPDATE notification_delivery
            SET delivery_status = 'RETRYABLE_FAILED',
                next_attempt_at = :nextAttemptAt,
                lease_until = NULL,
                last_error = :lastError,
                updated_at = :now
            WHERE communication_id = :communicationId
              AND delivery_status <> 'ACKED'
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Autowired
    public NotificationDeliveryRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate
    ) {
        this(jdbcTemplate, transactionTemplate, Clock.systemUTC());
    }

    NotificationDeliveryRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            Clock clock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public void saveAllIfAbsent(List<IncomingNotification> notifications) {
        if (notifications.isEmpty()) {
            return;
        }

        Instant now = clock.instant();
        SqlParameterSource[] batch = new SqlParameterSource[notifications.size()];
        for (int index = 0; index < notifications.size(); index++) {
            IncomingNotification notification = notifications.get(index);
            batch[index] = new MapSqlParameterSource()
                    .addValue("communicationId", notification.communicationId())
                    .addValue("recipientIspb", notification.recipientIspb())
                    .addValue("eventType", notification.eventType())
                    .addValue("paymentId", notification.paymentId())
                    .addValue("status", notification.status())
                    .addValue("schemaVersion", notification.schemaVersion())
                    .addValue("payload", notification.payload())
                    .addValue("deliveryStatus", DeliveryStatus.PENDING.name())
                    .addValue("nextAttemptAt", timestamp(now));
        }

        transactionTemplate.executeWithoutResult(
                ignored -> jdbcTemplate.batchUpdate(INSERT_SQL, batch)
        );
    }

    public List<NotificationDelivery> claimForLocalIspbs(
            Collection<String> localIspbs,
            int limit,
            Duration leaseDuration
    ) {
        if (localIspbs.isEmpty() || limit <= 0) {
            return List.of();
        }

        return transactionTemplate.execute(status -> {
            Instant now = clock.instant();
            Instant leaseUntil = now.plus(leaseDuration);
            return jdbcTemplate.query(CLAIM_SQL, new MapSqlParameterSource()
                            .addValue("ispbs", localIspbs)
                            .addValue("limit", limit)
                            .addValue("now", timestamp(now))
                            .addValue("leaseUntil", timestamp(leaseUntil)),
                    (rs, rowNum) -> new NotificationDelivery(
                            rs.getString("communication_id"),
                            rs.getString("recipient_ispb"),
                            rs.getBytes("payload")
                    ));
        });
    }

    public int acknowledgeAll(List<Acknowledgement> acknowledgements) {
        if (acknowledgements.isEmpty()) {
            return 0;
        }
        Integer updated = transactionTemplate.execute(ignored ->
                jdbcTemplate.getJdbcTemplate().execute((ConnectionCallback<Integer>) connection -> {
                    String[] communicationIds = acknowledgements.stream()
                            .map(Acknowledgement::communicationId)
                            .toArray(String[]::new);
                    String[] recipientIspbs = acknowledgements.stream()
                            .map(Acknowledgement::recipientIspb)
                            .toArray(String[]::new);
                    OffsetDateTime now = timestamp(clock.instant());
                    Array communicationIdArray = null;
                    Array recipientIspbArray = null;
                    try {
                        communicationIdArray = connection.createArrayOf("text", communicationIds);
                        recipientIspbArray = connection.createArrayOf("text", recipientIspbs);
                        try (PreparedStatement statement = connection.prepareStatement(ACK_ALL_SQL)) {
                            statement.setObject(1, now);
                            statement.setObject(2, now);
                            statement.setArray(3, communicationIdArray);
                            statement.setArray(4, recipientIspbArray);
                            try (ResultSet resultSet = statement.executeQuery()) {
                                int count = 0;
                                while (resultSet.next()) {
                                    count++;
                                }
                                return count;
                            }
                        }
                    } finally {
                        free(communicationIdArray, recipientIspbArray);
                    }
                }));
        return updated == null ? 0 : updated;
    }

    public void markRetryableFailed(String communicationId, String error, Duration retryDelay) {
        Instant now = clock.instant();
        jdbcTemplate.update(RETRYABLE_FAILED_SQL, new MapSqlParameterSource()
                .addValue("communicationId", communicationId)
                .addValue("lastError", truncate(error))
                .addValue("nextAttemptAt", timestamp(now.plus(retryDelay)))
                .addValue("now", timestamp(now)));
    }

    private OffsetDateTime timestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private void free(Array... arrays) throws SQLException {
        for (Array array : arrays) {
            if (array != null) {
                array.free();
            }
        }
    }

    private String truncate(String error) {
        if (error == null || error.length() <= 1_000) {
            return error;
        }
        return error.substring(0, 1_000);
    }
}
