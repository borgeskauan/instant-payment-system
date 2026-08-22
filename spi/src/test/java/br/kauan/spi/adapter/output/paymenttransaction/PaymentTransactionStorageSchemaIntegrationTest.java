package br.kauan.spi.adapter.output.paymenttransaction;

import br.kauan.spi.adapter.output.notification.OutboundNotificationPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
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

    @Test
    void auditEventsRetainOnlyBusinessInvariantIndexes() {
        List<String> indexNames = jdbcTemplate.queryForList(
                """
                        SELECT indexname
                        FROM pg_indexes
                        WHERE schemaname = current_schema()
                          AND tablename = 'payment_audit_event'
                        ORDER BY indexname
                        """,
                String.class
        );

        assertThat(indexNames).containsExactly(
                "uq_payment_audit_created",
                "uq_payment_audit_settlement"
        );
    }

    @Test
    void auditEventIdRemainsGeneratedAndRequiredWithoutBeingAPrimaryKey() {
        Map<String, Object> identity = jdbcTemplate.queryForMap(
                """
                        SELECT attnotnull, attidentity::text AS identity_kind
                        FROM pg_attribute
                        WHERE attrelid = 'payment_audit_event'::regclass
                          AND attname = 'event_id'
                        """
        );
        Integer primaryKeyCount = jdbcTemplate.queryForObject(
                """
                        SELECT count(*)
                        FROM pg_constraint
                        WHERE conrelid = 'payment_audit_event'::regclass
                          AND contype = 'p'
                        """,
                Integer.class
        );

        assertThat(identity)
                .containsEntry("attnotnull", true)
                .containsEntry("identity_kind", "a");
        assertThat(primaryKeyCount).isZero();
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
