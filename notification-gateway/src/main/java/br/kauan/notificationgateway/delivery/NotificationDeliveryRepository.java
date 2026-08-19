package br.kauan.notificationgateway.delivery;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
public class NotificationDeliveryRepository {

    private static final String INSERT_ALL_SQL = """
            WITH settings AS (
                SELECT ?::timestamptz AS persisted_at,
                       ?::timestamptz AS lease_until
            ), inserted AS (
                INSERT INTO notification_delivery (
                    communication_id,
                    recipient_ispb,
                    event_type,
                    payment_id,
                    notification_status,
                    schema_version,
                    payload,
                    delivery_status,
                    attempt_count,
                    next_attempt_at,
                    last_attempt_at
                )
                SELECT
                    incoming.communication_id,
                    incoming.recipient_ispb,
                    incoming.event_type,
                    incoming.payment_id,
                    incoming.notification_status,
                    incoming.schema_version,
                    incoming.payload,
                    CASE WHEN incoming.direct_delivery THEN 'IN_FLIGHT' ELSE 'PENDING' END,
                    CASE WHEN incoming.direct_delivery THEN 1 ELSE 0 END,
                    CASE WHEN incoming.direct_delivery THEN settings.lease_until ELSE settings.persisted_at END,
                    CASE WHEN incoming.direct_delivery THEN settings.persisted_at ELSE NULL END
                FROM unnest(
                    ?::text[],
                    ?::text[],
                    ?::text[],
                    ?::text[],
                    ?::text[],
                    ?::text[],
                    ?::bytea[],
                    ?::boolean[]
                ) AS incoming(
                    communication_id,
                    recipient_ispb,
                    event_type,
                    payment_id,
                    notification_status,
                    schema_version,
                    payload,
                    direct_delivery
                )
                CROSS JOIN settings
                ON CONFLICT (communication_id) DO NOTHING
                RETURNING communication_id, recipient_ispb, delivery_status
            )
            SELECT communication_id, recipient_ispb
            FROM inserted
            WHERE delivery_status = 'IN_FLIGHT'
            """;

    private static final String CLAIM_SQL = """
            WITH candidates AS (
                SELECT communication_id
                FROM notification_delivery
                WHERE recipient_ispb IN (:ispbs)
                  AND delivery_status IN ('PENDING', 'RETRYABLE_FAILED', 'IN_FLIGHT')
                  AND next_attempt_at <= :now
                ORDER BY next_attempt_at, communication_id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            UPDATE notification_delivery delivery
            SET delivery_status = 'IN_FLIGHT',
                attempt_count = attempt_count + 1,
                last_attempt_at = :now,
                next_attempt_at = :leaseUntil,
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

    public List<NotificationDelivery> saveAllIfAbsent(
            List<IncomingNotification> notifications,
            Set<String> connectedRecipients,
            Duration leaseDuration
    ) {
        if (notifications.isEmpty()) {
            return List.of();
        }

        Map<String, IncomingNotification> notificationsById = new HashMap<>();
        for (IncomingNotification notification : notifications) {
            notificationsById.putIfAbsent(notification.communicationId(), notification);
        }

        List<NotificationDelivery> insertedForDirectDelivery = transactionTemplate.execute(ignored ->
                jdbcTemplate.getJdbcTemplate().execute((ConnectionCallback<List<NotificationDelivery>>) connection -> {
                    String[] communicationIds = new String[notifications.size()];
                    String[] recipientIspbs = new String[notifications.size()];
                    String[] eventTypes = new String[notifications.size()];
                    String[] paymentIds = new String[notifications.size()];
                    String[] notificationStatuses = new String[notifications.size()];
                    String[] schemaVersions = new String[notifications.size()];
                    byte[][] payloads = new byte[notifications.size()][];
                    Boolean[] directDeliveryFlags = new Boolean[notifications.size()];
                    for (int index = 0; index < notifications.size(); index++) {
                        IncomingNotification notification = notifications.get(index);
                        communicationIds[index] = notification.communicationId();
                        recipientIspbs[index] = notification.recipientIspb();
                        eventTypes[index] = notification.eventType();
                        paymentIds[index] = notification.paymentId();
                        notificationStatuses[index] = notification.status();
                        schemaVersions[index] = notification.schemaVersion();
                        payloads[index] = notification.payload();
                        directDeliveryFlags[index] = connectedRecipients.contains(notification.recipientIspb());
                    }

                    Array communicationIdArray = null;
                    Array recipientIspbArray = null;
                    Array eventTypeArray = null;
                    Array paymentIdArray = null;
                    Array notificationStatusArray = null;
                    Array schemaVersionArray = null;
                    Array payloadArray = null;
                    Array directDeliveryArray = null;
                    try {
                        communicationIdArray = connection.createArrayOf("text", communicationIds);
                        recipientIspbArray = connection.createArrayOf("text", recipientIspbs);
                        eventTypeArray = connection.createArrayOf("text", eventTypes);
                        paymentIdArray = connection.createArrayOf("text", paymentIds);
                        notificationStatusArray = connection.createArrayOf("text", notificationStatuses);
                        schemaVersionArray = connection.createArrayOf("text", schemaVersions);
                        payloadArray = connection.createArrayOf("bytea", payloads);
                        directDeliveryArray = connection.createArrayOf("boolean", directDeliveryFlags);
                        try (PreparedStatement statement = connection.prepareStatement(INSERT_ALL_SQL)) {
                            Instant now = clock.instant();
                            statement.setObject(1, timestamp(now));
                            statement.setObject(2, timestamp(now.plus(leaseDuration)));
                            statement.setArray(3, communicationIdArray);
                            statement.setArray(4, recipientIspbArray);
                            statement.setArray(5, eventTypeArray);
                            statement.setArray(6, paymentIdArray);
                            statement.setArray(7, notificationStatusArray);
                            statement.setArray(8, schemaVersionArray);
                            statement.setArray(9, payloadArray);
                            statement.setArray(10, directDeliveryArray);
                            try (ResultSet rows = statement.executeQuery()) {
                                List<NotificationDelivery> result = new java.util.ArrayList<>();
                                while (rows.next()) {
                                    String communicationId = rows.getString("communication_id");
                                    IncomingNotification notification = notificationsById.get(communicationId);
                                    result.add(new NotificationDelivery(
                                            communicationId,
                                            rows.getString("recipient_ispb"),
                                            notification.payload()
                                    ));
                                }
                                return List.copyOf(result);
                            }
                        }
                    } finally {
                        free(
                                communicationIdArray,
                                recipientIspbArray,
                                eventTypeArray,
                                paymentIdArray,
                                notificationStatusArray,
                                schemaVersionArray,
                                payloadArray,
                                directDeliveryArray
                        );
                    }
                })
        );
        return insertedForDirectDelivery == null ? List.of() : insertedForDirectDelivery;
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
