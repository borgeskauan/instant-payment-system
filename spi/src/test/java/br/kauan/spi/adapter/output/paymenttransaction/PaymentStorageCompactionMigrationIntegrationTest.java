package br.kauan.spi.adapter.output.paymenttransaction;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentStorageCompactionMigrationIntegrationTest {

    private static final String LEGACY_FINGERPRINT =
            "3a7d0d942c81d34f73ac13714d9fb187e63e33a9ca505481ccc52ff36ca6c20c";

    @Test
    void migratesExistingPaymentAndAuditValuesWithoutChangingTheirMeaning() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")) {
            postgres.start();
            flyway(postgres, "13").migrate();
            insertLegacyRows(postgres);

            flyway(postgres, null).migrate();

            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()
            ); var statement = connection.createStatement()) {
                try (var payment = statement.executeQuery("""
                        SELECT
                            request_fingerprint,
                            request_fingerprint_version,
                            status::text,
                            rejection_reason::text
                        FROM payment_transaction_entity
                        WHERE payment_id = 'E2E-COMPACTION-LEGACY'
                        """)) {
                    assertThat(payment.next()).isTrue();
                    assertThat(payment.getBytes(1)).containsExactly(HexFormat.of().parseHex(LEGACY_FINGERPRINT));
                    assertThat(payment.getShort(2)).isEqualTo((short) 1);
                    assertThat(payment.getString(3)).isEqualTo("REJECTED");
                    assertThat(payment.getString(4)).isEqualTo("INSUFFICIENT_FUNDS");
                }
                try (var audit = statement.executeQuery("""
                        SELECT event_type::text, previous_status::text, resulting_status::text, reason::text
                        FROM payment_audit_event_history
                        WHERE payment_id = 'E2E-COMPACTION-LEGACY'
                        """)) {
                    assertThat(audit.next()).isTrue();
                    assertThat(audit.getString(1)).isEqualTo("PAYMENT_CREATED");
                    assertThat(audit.getString(2)).isNull();
                    assertThat(audit.getString(3)).isEqualTo("REJECTED");
                    assertThat(audit.getString(4)).isEqualTo("INSUFFICIENT_FUNDS");
                }
            }
        }
    }

    private void insertLegacyRows(PostgreSQLContainer<?> postgres) throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()
        ); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO payment_transaction_entity (
                        payment_id,
                        amount_cents,
                        status,
                        sender_bank_code,
                        receiver_bank_code,
                        request_fingerprint,
                        request_fingerprint_version,
                        rejection_reason
                    ) VALUES (
                        'E2E-COMPACTION-LEGACY',
                        1000,
                        'REJECTED',
                        '11111111',
                        '22222222',
                        '%s',
                        'v1',
                        'INSUFFICIENT_FUNDS'
                    )
                    """.formatted(LEGACY_FINGERPRINT));
            statement.executeUpdate("""
                    INSERT INTO payment_audit_event (
                        payment_id,
                        event_type,
                        resulting_status,
                        amount_cents,
                        sender_ispb,
                        receiver_ispb,
                        reason
                    ) VALUES (
                        'E2E-COMPACTION-LEGACY',
                        'PAYMENT_CREATED',
                        'REJECTED',
                        1000,
                        '11111111',
                        '22222222',
                        'INSUFFICIENT_FUNDS'
                    )
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
