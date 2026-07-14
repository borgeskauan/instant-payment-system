package br.kauan.notificationgateway.delivery;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

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

    private static final String ACK_SQL = """
            UPDATE notification_delivery
            SET delivery_status = 'ACKED',
                acknowledged_at = :now,
                lease_until = NULL,
                updated_at = :now
            WHERE communication_id = :communicationId
              AND delivery_status <> 'ACKED'
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

    public void saveIfAbsent(IncomingNotification notification) {
        Instant now = clock.instant();
        jdbcTemplate.update(INSERT_SQL, new MapSqlParameterSource()
                .addValue("communicationId", notification.communicationId())
                .addValue("recipientIspb", notification.recipientIspb())
                .addValue("eventType", notification.eventType())
                .addValue("paymentId", notification.paymentId())
                .addValue("status", notification.status())
                .addValue("schemaVersion", notification.schemaVersion())
                .addValue("payload", notification.payload())
                .addValue("deliveryStatus", DeliveryStatus.PENDING.name())
                .addValue("nextAttemptAt", timestamp(now)));
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

    public void acknowledge(String communicationId) {
        Instant now = clock.instant();
        jdbcTemplate.update(ACK_SQL, new MapSqlParameterSource()
                .addValue("communicationId", communicationId)
                .addValue("now", timestamp(now)));
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

    private String truncate(String error) {
        if (error == null || error.length() <= 1_000) {
            return error;
        }
        return error.substring(0, 1_000);
    }
}
