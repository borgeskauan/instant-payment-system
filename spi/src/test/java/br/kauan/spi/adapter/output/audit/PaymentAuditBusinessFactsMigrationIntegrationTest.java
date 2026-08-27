package br.kauan.spi.adapter.output.audit;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentAuditBusinessFactsMigrationIntegrationTest {

    @Test
    void archivesLegacyAuditRowsWithoutReinterpretingTheirBusinessMeaning() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")) {
            postgres.start();
            flyway(postgres, "16").migrate();
            insertLegacyAuditRows(postgres);

            flyway(postgres, null).migrate();

            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()
            ); var statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("""
                        SELECT
                            payment_id,
                            event_type::text,
                            previous_status::text,
                            resulting_status::text,
                            amount_cents,
                            sender_delta_cents,
                            receiver_delta_cents,
                            reason::text
                        FROM payment_audit_event_legacy_v16
                        ORDER BY payment_id, event_id
                        """)) {
                    assertThat(rows.next()).isTrue();
                    assertRow(rows, "E2E-AUDIT-LEGACY-INSUFFICIENT", "PAYMENT_CREATED",
                            null, "REJECTED", 1_000L, null, null, "INSUFFICIENT_FUNDS");

                    assertThat(rows.next()).isTrue();
                    assertRow(rows, "E2E-AUDIT-LEGACY-PROCESSING", "PAYMENT_CREATED",
                            null, "WAITING_ACCEPTANCE", 4_000L, null, null, null);
                    assertThat(rows.next()).isTrue();
                    assertRow(rows, "E2E-AUDIT-LEGACY-PROCESSING", "PAYMENT_STATUS_CHANGED",
                            "WAITING_ACCEPTANCE", "ACCEPTED_IN_PROCESS", null, null, null, null);

                    assertThat(rows.next()).isTrue();
                    assertRow(rows, "E2E-AUDIT-LEGACY-REJECTED", "PAYMENT_CREATED",
                            null, "WAITING_ACCEPTANCE", 2_000L, null, null, null);
                    assertThat(rows.next()).isTrue();
                    assertRow(rows, "E2E-AUDIT-LEGACY-REJECTED", "PAYMENT_STATUS_CHANGED",
                            "WAITING_ACCEPTANCE", "REJECTED", null, null, null, null);

                    assertThat(rows.next()).isTrue();
                    assertRow(rows, "E2E-AUDIT-LEGACY-SETTLED", "PAYMENT_CREATED",
                            null, "WAITING_ACCEPTANCE", 3_000L, null, null, null);
                    assertThat(rows.next()).isTrue();
                    assertRow(rows, "E2E-AUDIT-LEGACY-SETTLED", "PAYMENT_STATUS_CHANGED",
                            "WAITING_ACCEPTANCE", "ACCEPTED_AND_SETTLED", null, null, null, null);
                    assertThat(rows.next()).isTrue();
                    assertRow(rows, "E2E-AUDIT-LEGACY-SETTLED", "SETTLEMENT_APPLIED",
                            null, null, 3_000L, -3_000L, 3_000L, null);
                    assertThat(rows.next()).isFalse();
                }

                try (var currentCount = statement.executeQuery(
                        "SELECT count(*) FROM payment_audit_event"
                )) {
                    assertThat(currentCount.next()).isTrue();
                    assertThat(currentCount.getInt(1)).isZero();
                }

                try (var historyCount = statement.executeQuery(
                        "SELECT count(*) FROM payment_audit_event_history"
                )) {
                    assertThat(historyCount.next()).isTrue();
                    assertThat(historyCount.getInt(1)).isEqualTo(8);
                }

                try (var enumValues = statement.executeQuery("""
                        SELECT enumlabel
                        FROM pg_enum
                        JOIN pg_type ON pg_type.oid = pg_enum.enumtypid
                        WHERE pg_type.typname = 'payment_audit_event_type'
                        ORDER BY enumsortorder
                        """)) {
                    assertThat(enumValues.next()).isTrue();
                    assertThat(enumValues.getString(1)).isEqualTo("PAYMENT_RESERVED");
                    assertThat(enumValues.next()).isTrue();
                    assertThat(enumValues.getString(1)).isEqualTo("PAYMENT_SETTLED");
                    assertThat(enumValues.next()).isTrue();
                    assertThat(enumValues.getString(1)).isEqualTo("PAYMENT_REJECTED");
                    assertThat(enumValues.next()).isFalse();
                }
            }
        }
    }

    private void assertRow(
            java.sql.ResultSet row,
            String paymentId,
            String eventType,
            String previousStatus,
            String resultingStatus,
            Long amountCents,
            Long senderDeltaCents,
            Long receiverDeltaCents,
            String reason
    ) throws Exception {
        assertThat(row.getString("payment_id")).isEqualTo(paymentId);
        assertThat(row.getString("event_type")).isEqualTo(eventType);
        assertThat(row.getString("previous_status")).isEqualTo(previousStatus);
        assertThat(row.getString("resulting_status")).isEqualTo(resultingStatus);
        assertThat(row.getObject("amount_cents", Long.class)).isEqualTo(amountCents);
        assertThat(row.getObject("sender_delta_cents", Long.class)).isEqualTo(senderDeltaCents);
        assertThat(row.getObject("receiver_delta_cents", Long.class)).isEqualTo(receiverDeltaCents);
        assertThat(row.getString("reason")).isEqualTo(reason);
    }

    private void insertLegacyAuditRows(PostgreSQLContainer<?> postgres) throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()
        ); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO payment_audit_event (
                        payment_id, event_type, previous_status, resulting_status,
                        amount_cents, sender_ispb, receiver_ispb,
                        sender_delta_cents, receiver_delta_cents, reason
                    ) VALUES
                        ('E2E-AUDIT-LEGACY-INSUFFICIENT', 'PAYMENT_CREATED', NULL, 'REJECTED',
                         1000, '11111111', '22222222', NULL, NULL, 'INSUFFICIENT_FUNDS'),
                        ('E2E-AUDIT-LEGACY-REJECTED', 'PAYMENT_CREATED', NULL, 'WAITING_ACCEPTANCE',
                         2000, '11111111', '22222222', NULL, NULL, NULL),
                        ('E2E-AUDIT-LEGACY-REJECTED', 'PAYMENT_STATUS_CHANGED',
                         'WAITING_ACCEPTANCE', 'REJECTED', NULL, NULL, NULL, NULL, NULL, NULL),
                        ('E2E-AUDIT-LEGACY-PROCESSING', 'PAYMENT_CREATED', NULL, 'WAITING_ACCEPTANCE',
                         4000, '11111111', '22222222', NULL, NULL, NULL),
                        ('E2E-AUDIT-LEGACY-PROCESSING', 'PAYMENT_STATUS_CHANGED',
                         'WAITING_ACCEPTANCE', 'ACCEPTED_IN_PROCESS', NULL, NULL, NULL, NULL, NULL, NULL),
                        ('E2E-AUDIT-LEGACY-SETTLED', 'PAYMENT_CREATED', NULL, 'WAITING_ACCEPTANCE',
                         3000, '11111111', '22222222', NULL, NULL, NULL),
                        ('E2E-AUDIT-LEGACY-SETTLED', 'PAYMENT_STATUS_CHANGED',
                         'WAITING_ACCEPTANCE', 'ACCEPTED_AND_SETTLED', NULL, NULL, NULL, NULL, NULL, NULL),
                        ('E2E-AUDIT-LEGACY-SETTLED', 'SETTLEMENT_APPLIED', NULL, NULL,
                         3000, '11111111', '22222222', -3000, 3000, NULL)
                    """);
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
