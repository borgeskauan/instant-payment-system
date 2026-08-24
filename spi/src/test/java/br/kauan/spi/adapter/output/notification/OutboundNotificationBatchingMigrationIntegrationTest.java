package br.kauan.spi.adapter.output.notification;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundNotificationBatchingMigrationIntegrationTest {

    @Test
    void removesPerItemMetadataWithoutRewritingExistingMessageIdentityOrPayload() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")) {
            postgres.start();
            Flyway beforeBatching = flyway(postgres, "12");
            beforeBatching.migrate();

            byte[] payload = "legacy-payload".getBytes(StandardCharsets.UTF_8);
            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()
            ); var statement = connection.prepareStatement("""
                    INSERT INTO outbound_notification (
                        communication_id,
                        recipient_ispb,
                        event_type,
                        payment_id,
                        notification_status,
                        schema_version,
                        payload,
                        created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """)) {
                statement.setString(1, "v1:legacy-text-identity");
                statement.setString(2, "20000001");
                statement.setString(3, "SETTLED_NOTIFICATION");
                statement.setString(4, "E2E-LEGACY");
                statement.setString(5, "ACSC");
                statement.setString(6, "v1");
                statement.setBytes(7, payload);
                statement.executeUpdate();
            }

            flyway(postgres, null).migrate();

            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()
            ); var statement = connection.createStatement()) {
                try (var columns = statement.executeQuery("""
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'notification_outbox'
                        ORDER BY ordinal_position
                        """)) {
                    var columnNames = new ArrayList<String>();
                    while (columns.next()) {
                        columnNames.add(columns.getString(1));
                    }
                    assertThat(columnNames).containsExactly(
                            "communication_id",
                            "recipient_ispb",
                            "payload",
                            "created_at"
                    );
                }
                try (var rows = statement.executeQuery("""
                        SELECT communication_id, recipient_ispb, payload
                        FROM notification_outbox
                        """)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("communication_id")).isEqualTo("v1:legacy-text-identity");
                    assertThat(rows.getString("recipient_ispb")).isEqualTo("20000001");
                    assertThat(rows.getBytes("payload")).isEqualTo(payload);
                    assertThat(rows.next()).isFalse();
                }
            }
        }
    }

    private Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .table("spi_flyway_schema_history");
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(target));
        }
        return configuration.load();
    }
}
