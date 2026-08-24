package br.kauan.spi.adapter.output.notification;

import br.kauan.spi.adapter.output.kafka.NotificationPublication;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

@Repository
public class OutboundNotificationRepository {

    private static final String INSERT_ALL_SQL = """
            INSERT INTO notification_outbox (
                communication_id,
                recipient_ispb,
                payload,
                created_at
            )
            SELECT
                incoming.communication_id,
                incoming.recipient_ispb,
                incoming.payload,
                CURRENT_TIMESTAMP
            FROM unnest(
                ?::text[],
                ?::text[],
                ?::bytea[]
            ) AS incoming(
                communication_id,
                recipient_ispb,
                payload
            )
            """;

    private static final String FIND_OLDEST_SQL = """
            SELECT communication_id, recipient_ispb, payload
            FROM notification_outbox
            ORDER BY created_at, communication_id
            LIMIT ?
            """;

    private static final String DELETE_ALL_SQL = """
            DELETE FROM notification_outbox
            WHERE communication_id = ANY (?::text[])
            """;

    private final JdbcTemplate jdbcTemplate;

    public OutboundNotificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertAll(List<NotificationPublication> notifications) {
        if (notifications.isEmpty()) {
            return;
        }

        Integer inserted = jdbcTemplate.execute((ConnectionCallback<Integer>) connection -> {
            String[] communicationIds = new String[notifications.size()];
            String[] recipientIspbs = new String[notifications.size()];
            byte[][] payloads = new byte[notifications.size()][];
            for (int index = 0; index < notifications.size(); index++) {
                NotificationPublication notification = notifications.get(index);
                communicationIds[index] = notification.communicationId();
                recipientIspbs[index] = notification.recipientIspb();
                payloads[index] = notification.payload();
            }

            Array communicationIdArray = null;
            Array recipientIspbArray = null;
            Array payloadArray = null;
            try {
                communicationIdArray = connection.createArrayOf("text", communicationIds);
                recipientIspbArray = connection.createArrayOf("text", recipientIspbs);
                payloadArray = connection.createArrayOf("bytea", payloads);
                try (PreparedStatement statement = connection.prepareStatement(INSERT_ALL_SQL)) {
                    statement.setArray(1, communicationIdArray);
                    statement.setArray(2, recipientIspbArray);
                    statement.setArray(3, payloadArray);
                    return statement.executeUpdate();
                }
            } finally {
                free(
                        communicationIdArray,
                        recipientIspbArray,
                        payloadArray
                );
            }
        });
        if (inserted == null || inserted != notifications.size()) {
            throw new IllegalStateException(
                    "Outbound notification insert count mismatch: requested="
                            + notifications.size() + ", inserted=" + inserted
            );
        }
    }

    public List<NotificationPublication> findOldest(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Outbox recovery limit must be positive");
        }
        return jdbcTemplate.query(
                FIND_OLDEST_SQL,
                (resultSet, ignored) -> NotificationPublication.create(
                        resultSet.getString("recipient_ispb"),
                        resultSet.getBytes("payload"),
                        resultSet.getString("communication_id")
                ),
                limit
        );
    }

    public void deleteAll(List<String> communicationIds) {
        if (communicationIds.isEmpty()) {
            return;
        }
        Integer deleted = jdbcTemplate.execute((ConnectionCallback<Integer>) connection -> {
            Array communicationIdArray = null;
            try {
                communicationIdArray = connection.createArrayOf("text", communicationIds.toArray(String[]::new));
                try (PreparedStatement statement = connection.prepareStatement(DELETE_ALL_SQL)) {
                    statement.setArray(1, communicationIdArray);
                    return statement.executeUpdate();
                }
            } finally {
                free(communicationIdArray);
            }
        });
        if (deleted == null || deleted != communicationIds.size()) {
            throw new IllegalStateException(
                    "Outbox delete count mismatch: requested=" + communicationIds.size() + ", deleted=" + deleted
            );
        }
    }

    private void free(Array... arrays) throws SQLException {
        for (Array array : arrays) {
            if (array != null) {
                array.free();
            }
        }
    }
}
