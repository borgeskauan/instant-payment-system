package br.kauan.spi.adapter.output.notification;

import br.kauan.spi.adapter.output.kafka.NotificationPublication;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Repository
public class OutboundNotificationRepository {

    private static final String INSERT_ALL_SQL = """
            INSERT INTO outbound_notification (
                communication_id,
                recipient_ispb,
                event_type,
                payment_id,
                notification_status,
                schema_version,
                payload,
                created_at
            )
            SELECT
                incoming.communication_id,
                incoming.recipient_ispb,
                incoming.event_type,
                incoming.payment_id,
                incoming.notification_status,
                incoming.schema_version,
                incoming.payload,
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
            RETURNING communication_id
            """;

    private final JdbcTemplate jdbcTemplate;

    public OutboundNotificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<NotificationPublication> insertAll(List<NotificationPublication> notifications) {
        if (notifications.isEmpty()) {
            return List.of();
        }

        return jdbcTemplate.execute((ConnectionCallback<List<NotificationPublication>>) connection -> {
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
                    Set<String> insertedIds = new LinkedHashSet<>();
                    try (ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            insertedIds.add(resultSet.getString("communication_id"));
                        }
                    }
                    List<NotificationPublication> inserted = new ArrayList<>(insertedIds.size());
                    for (NotificationPublication notification : notifications) {
                        if (insertedIds.remove(notification.communicationId())) {
                            inserted.add(notification);
                        }
                    }
                    return List.copyOf(inserted);
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

    private void free(Array... arrays) throws SQLException {
        for (Array array : arrays) {
            if (array != null) {
                array.free();
            }
        }
    }
}
