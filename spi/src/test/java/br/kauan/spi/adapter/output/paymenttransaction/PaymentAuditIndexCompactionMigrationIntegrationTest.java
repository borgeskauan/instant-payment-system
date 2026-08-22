package br.kauan.spi.adapter.output.paymenttransaction;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentAuditIndexCompactionMigrationIntegrationTest {

    @Test
    void removesTechnicalIndexesWithoutChangingExistingAuditEventIds() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")) {
            postgres.start();
            flyway(postgres, "14").migrate();
            long eventId = insertAuditEvent(postgres);

            flyway(postgres, null).migrate();

            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()
            ); var statement = connection.createStatement()) {
                try (var event = statement.executeQuery("""
                        SELECT event_id
                        FROM payment_audit_event
                        WHERE payment_id = 'E2E-AUDIT-INDEX-COMPACTION'
                        """)) {
                    assertThat(event.next()).isTrue();
                    assertThat(event.getLong(1)).isEqualTo(eventId);
                }

                try (var indexes = statement.executeQuery("""
                        SELECT indexname
                        FROM pg_indexes
                        WHERE schemaname = current_schema()
                          AND tablename = 'payment_audit_event'
                        ORDER BY indexname
                        """)) {
                    assertThat(indexes.next()).isTrue();
                    assertThat(indexes.getString(1)).isEqualTo("uq_payment_audit_created");
                    assertThat(indexes.next()).isTrue();
                    assertThat(indexes.getString(1)).isEqualTo("uq_payment_audit_settlement");
                    assertThat(indexes.next()).isFalse();
                }
            }
        }
    }

    private long insertAuditEvent(PostgreSQLContainer<?> postgres) throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()
        ); var statement = connection.createStatement(); var event = statement.executeQuery("""
                INSERT INTO payment_audit_event (
                    payment_id,
                    event_type,
                    resulting_status,
                    amount_cents,
                    sender_ispb,
                    receiver_ispb
                ) VALUES (
                    'E2E-AUDIT-INDEX-COMPACTION',
                    'PAYMENT_CREATED',
                    'WAITING_ACCEPTANCE',
                    1000,
                    '11111111',
                    '22222222'
                )
                RETURNING event_id
                """)) {
            assertThat(event.next()).isTrue();
            return event.getLong(1);
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
