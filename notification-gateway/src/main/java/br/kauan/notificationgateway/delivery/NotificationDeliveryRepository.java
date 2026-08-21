package br.kauan.notificationgateway.delivery;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Repository
public class NotificationDeliveryRepository {

    /* Serializes position allocation with transaction commit order across Kafka consumer threads. */
    private static final long POSITION_ALLOCATION_LOCK = 0x4e4f54494649434cL;

    private static final String INSERT_ALL_SQL = """
            INSERT INTO notification_delivery (
                communication_id,
                recipient_ispb,
                event_type,
                payment_id,
                notification_status,
                schema_version,
                payload,
                delivery_position
            )
            SELECT
                incoming.communication_id,
                incoming.recipient_ispb,
                incoming.event_type,
                incoming.payment_id,
                incoming.notification_status,
                incoming.schema_version,
                incoming.payload,
                nextval('notification_delivery_position_seq')
            FROM unnest(
                ?::text[],
                ?::text[],
                ?::text[],
                ?::text[],
                ?::text[],
                ?::text[],
                ?::bytea[]
            ) WITH ORDINALITY AS incoming(
                communication_id,
                recipient_ispb,
                event_type,
                payment_id,
                notification_status,
                schema_version,
                payload,
                source_order
            )
            ORDER BY incoming.source_order
            ON CONFLICT (communication_id) DO NOTHING
            RETURNING recipient_ispb
            """;

    private static final String FIND_AFTER_SQL = """
            SELECT delivery_position, communication_id, recipient_ispb, payload
            FROM notification_delivery
            WHERE recipient_ispb = ?
              AND delivery_position > ?
            ORDER BY delivery_position
            LIMIT ?
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public NotificationDeliveryRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    public Set<String> saveAllIfAbsent(List<IncomingNotification> notifications) {
        if (notifications.isEmpty()) {
            return Set.of();
        }

        Set<String> insertedRecipients = transactionTemplate.execute(ignored -> {
            jdbcTemplate.getJdbcTemplate().queryForObject(
                    "SELECT pg_advisory_xact_lock(?)::text",
                    String.class,
                    POSITION_ALLOCATION_LOCK
            );
            return jdbcTemplate.getJdbcTemplate().execute(
                    (ConnectionCallback<Set<String>>) connection -> {
                        String[] communicationIds = new String[notifications.size()];
                        String[] recipientIspbs = new String[notifications.size()];
                        String[] eventTypes = new String[notifications.size()];
                        String[] paymentIds = new String[notifications.size()];
                        String[] notificationStatuses = new String[notifications.size()];
                        String[] schemaVersions = new String[notifications.size()];
                        byte[][] payloads = new byte[notifications.size()][];
                        for (int index = 0; index < notifications.size(); index++) {
                            IncomingNotification notification = notifications.get(index);
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
                                try (ResultSet rows = statement.executeQuery()) {
                                    Set<String> recipients = new LinkedHashSet<>();
                                    while (rows.next()) {
                                        recipients.add(rows.getString("recipient_ispb"));
                                    }
                                    return Set.copyOf(recipients);
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
                                    payloadArray
                            );
                        }
                    }
            );
        });
        return insertedRecipients == null ? Set.of() : insertedRecipients;
    }

    public List<NotificationDelivery> findAfter(String recipientIspb, long position, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return jdbcTemplate.getJdbcTemplate().query(
                FIND_AFTER_SQL,
                (rows, ignored) -> new NotificationDelivery(
                        rows.getLong("delivery_position"),
                        rows.getString("communication_id"),
                        rows.getString("recipient_ispb"),
                        rows.getBytes("payload")
                ),
                recipientIspb,
                position,
                limit
        );
    }

    private static void free(Array... arrays) throws SQLException {
        for (Array array : arrays) {
            if (array != null) {
                array.free();
            }
        }
    }
}
