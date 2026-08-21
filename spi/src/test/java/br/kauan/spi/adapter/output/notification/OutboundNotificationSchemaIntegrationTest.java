package br.kauan.spi.adapter.output.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OutboundNotificationSchemaIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrationLeavesOnlyTheImmutableOutboundNotificationSchema() {
        assertThat(regclass("outbound_notification")).isEqualTo("outbound_notification");
        assertThat(regclass("notification_outbox")).isNull();
        assertThat(regclass("notification_outbox_pending_idx")).isNull();
        assertThat(columns()).containsExactly(
                "communication_id",
                "recipient_ispb",
                "event_type",
                "payment_id",
                "notification_status",
                "schema_version",
                "payload",
                "created_at"
        );
        assertThat(primaryKeyName()).isEqualTo("outbound_notification_pkey");
    }

    private String regclass(String relationName) {
        return jdbcTemplate.queryForObject("SELECT to_regclass(?)::text", String.class, "public." + relationName);
    }

    private List<String> columns() {
        return jdbcTemplate.queryForList(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'outbound_notification'
                ORDER BY ordinal_position
                """,
                String.class
        );
    }

    private String primaryKeyName() {
        return jdbcTemplate.queryForObject(
                """
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = 'outbound_notification'
                  AND constraint_type = 'PRIMARY KEY'
                """,
                String.class
        );
    }
}
