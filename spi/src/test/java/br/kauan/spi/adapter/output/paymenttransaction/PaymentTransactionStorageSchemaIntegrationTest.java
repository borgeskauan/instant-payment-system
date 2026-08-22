package br.kauan.spi.adapter.output.paymenttransaction;

import br.kauan.spi.adapter.output.notification.OutboundNotificationPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PaymentTransactionStorageSchemaIntegrationTest {

    @MockitoBean
    private OutboundNotificationPublisher outboundNotificationPublisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrationReservesHalfOfPaymentPagesForHotUpdates() {
        String relationOptions = jdbcTemplate.queryForObject(
                """
                        SELECT COALESCE(array_to_string(reloptions, ','), '')
                        FROM pg_class
                        WHERE oid = 'payment_transaction_entity'::regclass
                        """,
                String.class
        );

        assertThat(relationOptions).contains("fillfactor=50");
    }

    @Test
    void migrationUsesCompactFingerprintAndReadableCategoricalTypes() {
        assertThat(columnTypes("payment_transaction_entity")).containsAllEntriesOf(Map.of(
                "request_fingerprint", "bytea",
                "request_fingerprint_version", "smallint",
                "status", "payment_status",
                "rejection_reason", "payment_rejection_reason"
        ));
        assertThat(columnTypes("payment_audit_event")).containsAllEntriesOf(Map.of(
                "event_type", "payment_audit_event_type",
                "previous_status", "payment_status",
                "resulting_status", "payment_status",
                "reason", "payment_rejection_reason"
        ));
    }

    private Map<String, String> columnTypes(String tableName) {
        return jdbcTemplate.query(
                """
                        SELECT attribute.attname, format_type(attribute.atttypid, attribute.atttypmod)
                        FROM pg_attribute attribute
                        WHERE attribute.attrelid = ?::regclass
                          AND attribute.attnum > 0
                          AND NOT attribute.attisdropped
                        """,
                resultSet -> {
                    Map<String, String> types = new java.util.LinkedHashMap<>();
                    while (resultSet.next()) {
                        types.put(resultSet.getString(1), resultSet.getString(2));
                    }
                    return types;
                },
                tableName
        );
    }
}
