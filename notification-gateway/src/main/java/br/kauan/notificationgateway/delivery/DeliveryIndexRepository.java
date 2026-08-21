package br.kauan.notificationgateway.delivery;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
public class DeliveryIndexRepository {

    private static final int ADVISORY_LOCK_NAMESPACE = 0x4e4f5449;

    private static final String LOCK_RECIPIENTS_SQL = """
            SELECT pg_advisory_xact_lock(?, hashtext(locked.recipient_ispb))
            FROM unnest(?::text[]) WITH ORDINALITY AS locked(recipient_ispb, source_order)
            ORDER BY locked.source_order
            """;

    private static final String INSERT_ALL_SQL = """
            INSERT INTO delivery_index (
                communication_id,
                recipient_ispb,
                delivery_position
            )
            SELECT
                incoming.communication_id,
                incoming.recipient_ispb,
                incoming.delivery_position
            FROM unnest(
                ?::text[],
                ?::text[],
                ?::bigint[]
            ) WITH ORDINALITY AS incoming(
                communication_id,
                recipient_ispb,
                delivery_position,
                source_order
            )
            ORDER BY incoming.source_order
            """;

    private static final String FIND_AFTER_SQL = """
            SELECT
                index.delivery_position,
                index.communication_id,
                index.recipient_ispb,
                outbox.payload
            FROM delivery_index AS index
            JOIN notification_outbox AS outbox
              ON outbox.communication_id = index.communication_id
            WHERE index.recipient_ispb = ?
              AND index.delivery_position > ?
            ORDER BY index.delivery_position
            LIMIT ?
            """;

    private static final String FIND_LAST_POSITIONS_SQL = """
            SELECT
                recipient.recipient_ispb,
                COALESCE(last_index.delivery_position, 0) AS last_position
            FROM unnest(?::text[]) AS recipient(recipient_ispb)
            LEFT JOIN LATERAL (
                SELECT delivery_position
                FROM delivery_index
                WHERE recipient_ispb = recipient.recipient_ispb
                ORDER BY delivery_position DESC
                LIMIT 1
            ) AS last_index ON TRUE
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public DeliveryIndexRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    public List<NotificationDelivery> indexNew(List<IncomingNotification> notifications) {
        List<IncomingNotification> uniqueNotifications = uniqueNotifications(notifications);
        if (uniqueNotifications.isEmpty()) {
            return List.of();
        }

        List<NotificationDelivery> indexed = transactionTemplate.execute(ignored -> {
            List<String> recipients = uniqueNotifications.stream()
                    .map(IncomingNotification::recipientIspb)
                    .distinct()
                    .sorted()
                    .toList();
            lockRecipients(recipients);

            Map<String, String> existingRecipients = existingRecipients(uniqueNotifications);
            List<IncomingNotification> newNotifications = new ArrayList<>(uniqueNotifications.size());
            for (IncomingNotification notification : uniqueNotifications) {
                String existingRecipient = existingRecipients.get(notification.communicationId());
                if (existingRecipient == null) {
                    newNotifications.add(notification);
                } else if (!existingRecipient.equals(notification.recipientIspb())) {
                    throw conflictingRecipient(notification, existingRecipient);
                }
            }
            if (newNotifications.isEmpty()) {
                return List.of();
            }

            Map<String, Long> nextPositions = lastPositions(recipients);
            List<NotificationDelivery> newIndexes = new ArrayList<>(newNotifications.size());
            for (IncomingNotification notification : newNotifications) {
                long position = nextPositions.merge(notification.recipientIspb(), 1L, Long::sum);
                newIndexes.add(new NotificationDelivery(
                        position,
                        notification.communicationId(),
                        notification.recipientIspb(),
                        notification.payload()
                ));
            }
            insertAll(newIndexes);
            return List.copyOf(newIndexes);
        });
        return indexed == null ? List.of() : indexed;
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

    private List<IncomingNotification> uniqueNotifications(List<IncomingNotification> notifications) {
        LinkedHashMap<String, IncomingNotification> unique = new LinkedHashMap<>();
        for (IncomingNotification notification : notifications) {
            if (notification.communicationId() == null || notification.communicationId().isBlank()) {
                throw new IllegalArgumentException("communication ID is required");
            }
            if (notification.recipientIspb() == null || notification.recipientIspb().isBlank()) {
                throw new IllegalArgumentException("recipient ISPB is required");
            }
            if (notification.payload() == null) {
                throw new IllegalArgumentException("notification payload is required");
            }
            IncomingNotification existing = unique.putIfAbsent(notification.communicationId(), notification);
            if (existing != null && !existing.recipientIspb().equals(notification.recipientIspb())) {
                throw conflictingRecipient(notification, existing.recipientIspb());
            }
        }
        return List.copyOf(unique.values());
    }

    private void lockRecipients(List<String> recipients) {
        jdbcTemplate.getJdbcTemplate().execute((ConnectionCallback<Void>) connection -> {
            Array recipientArray = null;
            try {
                recipientArray = connection.createArrayOf("text", recipients.toArray(String[]::new));
                try (PreparedStatement statement = connection.prepareStatement(LOCK_RECIPIENTS_SQL)) {
                    statement.setInt(1, ADVISORY_LOCK_NAMESPACE);
                    statement.setArray(2, recipientArray);
                    statement.executeQuery().close();
                }
                return null;
            } finally {
                free(recipientArray);
            }
        });
    }

    private Map<String, String> existingRecipients(List<IncomingNotification> notifications) {
        Set<String> communicationIds = new LinkedHashSet<>();
        for (IncomingNotification notification : notifications) {
            communicationIds.add(notification.communicationId());
        }
        return jdbcTemplate.query(
                """
                SELECT communication_id, recipient_ispb
                FROM delivery_index
                WHERE communication_id IN (:communicationIds)
                """,
                new MapSqlParameterSource("communicationIds", communicationIds),
                rows -> {
                    Map<String, String> existing = new HashMap<>();
                    while (rows.next()) {
                        existing.put(rows.getString("communication_id"), rows.getString("recipient_ispb"));
                    }
                    return existing;
                }
        );
    }

    private Map<String, Long> lastPositions(List<String> recipients) {
        return jdbcTemplate.getJdbcTemplate().execute((ConnectionCallback<Map<String, Long>>) connection -> {
            Array recipientArray = null;
            try {
                recipientArray = connection.createArrayOf("text", recipients.toArray(String[]::new));
                try (PreparedStatement statement = connection.prepareStatement(FIND_LAST_POSITIONS_SQL)) {
                    statement.setArray(1, recipientArray);
                    Map<String, Long> positions = new HashMap<>();
                    var rows = statement.executeQuery();
                    while (rows.next()) {
                        positions.put(rows.getString("recipient_ispb"), rows.getLong("last_position"));
                    }
                    rows.close();
                    return positions;
                }
            } finally {
                free(recipientArray);
            }
        });
    }

    private void insertAll(List<NotificationDelivery> deliveries) {
        jdbcTemplate.getJdbcTemplate().execute((ConnectionCallback<Void>) connection -> {
            String[] communicationIds = new String[deliveries.size()];
            String[] recipientIspbs = new String[deliveries.size()];
            Long[] positions = new Long[deliveries.size()];
            for (int index = 0; index < deliveries.size(); index++) {
                NotificationDelivery delivery = deliveries.get(index);
                communicationIds[index] = delivery.communicationId();
                recipientIspbs[index] = delivery.recipientIspb();
                positions[index] = delivery.deliveryPosition();
            }

            Array communicationIdArray = null;
            Array recipientIspbArray = null;
            Array positionArray = null;
            try {
                communicationIdArray = connection.createArrayOf("text", communicationIds);
                recipientIspbArray = connection.createArrayOf("text", recipientIspbs);
                positionArray = connection.createArrayOf("bigint", positions);
                try (PreparedStatement statement = connection.prepareStatement(INSERT_ALL_SQL)) {
                    statement.setArray(1, communicationIdArray);
                    statement.setArray(2, recipientIspbArray);
                    statement.setArray(3, positionArray);
                    int inserted = statement.executeUpdate();
                    if (inserted != deliveries.size()) {
                        throw new IllegalStateException(
                                "delivery index insert affected " + inserted + " rows, expected " + deliveries.size()
                        );
                    }
                }
                return null;
            } finally {
                free(communicationIdArray, recipientIspbArray, positionArray);
            }
        });
    }

    private IllegalStateException conflictingRecipient(
            IncomingNotification notification,
            String existingRecipient
    ) {
        return new IllegalStateException(
                "communication ID " + notification.communicationId()
                        + " belongs to recipient " + existingRecipient
                        + ", not " + notification.recipientIspb()
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
