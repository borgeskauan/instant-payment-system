package br.kauan.notificationgateway.delivery;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryIndexMigrationIntegrationTest {

    @Test
    void refusesToReplaceANonEmptyDeliveryTableAndPreservesItsRows() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")) {
            postgres.start();
            Flyway phaseThree = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .table("notification_gateway_flyway_schema_history")
                    .target(MigrationVersion.fromVersion("3"))
                    .load();
            phaseThree.migrate();

            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()
            ); var statement = connection.prepareStatement("""
                    INSERT INTO notification_delivery (
                        communication_id,
                        recipient_ispb,
                        event_type,
                        payment_id,
                        schema_version,
                        payload,
                        created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """)) {
                statement.setString(1, "v1:existing");
                statement.setString(2, "20000001");
                statement.setString(3, "SETTLED_NOTIFICATION");
                statement.setString(4, "E2E-1");
                statement.setString(5, "v1");
                statement.setBytes(6, "payload".getBytes(StandardCharsets.UTF_8));
                statement.executeUpdate();
            }

            Flyway phaseFour = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .table("notification_gateway_flyway_schema_history")
                    .load();

            assertThatThrownBy(phaseFour::migrate)
                    .hasRootCauseMessage(
                            "ERROR: notification_delivery must be empty before migrating to delivery_index\n"
                                    + "  Where: PL/pgSQL function inline_code_block line 4 at RAISE"
                    );

            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()
            ); var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM notification_delivery"
            )) {
                rows.next();
                assertThat(rows.getInt(1)).isOne();
            }
        }
    }
}
