package br.kauan.notificationgateway.delivery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(NotificationReconciliationRepository.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17-alpine:///notification_reconciliation_repository_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
        "spring.flyway.table=notification_gateway_flyway_schema_history",
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.baseline-version=0"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext
class NotificationReconciliationRepositoryIntegrationTest {

    @Autowired
    private NotificationReconciliationRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetTables() {
        jdbcTemplate.update("TRUNCATE delivery_index");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS outbound_notification (
                    communication_id TEXT PRIMARY KEY,
                    recipient_ispb TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    payment_id TEXT NOT NULL,
                    notification_status TEXT,
                    schema_version TEXT NOT NULL,
                    payload BYTEA NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.update("TRUNCATE outbound_notification");
    }

    @Test
    void returnsOnlyCanonicalNotificationsWithoutADeliveryIndex() {
        storeOutboundNotification("v1:first", "20000001", "ACSC", "first");
        storeOutboundNotification("v1:published", "20000002", "RJCT", "published");
        storeOutboundNotification("v1:indexed", "20000001", "RJCT", "indexed");
        jdbcTemplate.update("""
                INSERT INTO delivery_index (communication_id, recipient_ispb, delivery_position)
                VALUES ('v1:indexed', '20000001', 1)
                """);

        List<IncomingNotification> notifications = repository.findUnindexedAfter("", 1_000);

        assertThat(notifications).hasSize(2);
        assertThat(notifications.getFirst()).satisfies(notification -> {
            assertThat(notification.communicationId()).isEqualTo("v1:first");
            assertThat(notification.recipientIspb()).isEqualTo("20000001");
            assertThat(notification.eventType()).isEqualTo("SETTLED_NOTIFICATION");
            assertThat(notification.paymentId()).isEqualTo("E2E-v1:first");
            assertThat(notification.status()).isEqualTo("ACSC");
            assertThat(notification.schemaVersion()).isEqualTo("v1");
            assertThat(notification.payload()).isEqualTo(bytes("first"));
        });
        assertThat(notifications.getLast()).satisfies(notification -> {
            assertThat(notification.communicationId()).isEqualTo("v1:published");
            assertThat(notification.status()).isEqualTo("RJCT");
            assertThat(notification.payload()).isEqualTo(bytes("published"));
        });
    }

    @Test
    void pagesByCommunicationIdWithoutPersistingAReconciliationCursor() {
        storeOutboundNotification("v1:c", "20000001", "ACSC", "c");
        storeOutboundNotification("v1:a", "20000001", "ACSC", "a");
        storeOutboundNotification("v1:b", "20000001", "ACSC", "b");

        List<IncomingNotification> firstPage = repository.findUnindexedAfter("", 2);
        List<IncomingNotification> secondPage = repository.findUnindexedAfter(
                firstPage.getLast().communicationId(),
                2
        );
        List<IncomingNotification> restartedScan = repository.findUnindexedAfter("", 1);

        assertThat(firstPage).extracting(IncomingNotification::communicationId)
                .containsExactly("v1:a", "v1:b");
        assertThat(secondPage).extracting(IncomingNotification::communicationId)
                .containsExactly("v1:c");
        assertThat(restartedScan).extracting(IncomingNotification::communicationId)
                .containsExactly("v1:a");
    }

    @Test
    void nonPositiveLimitReturnsNoRows() {
        storeOutboundNotification("v1:first", "20000001", "ACSC", "first");

        assertThat(repository.findUnindexedAfter("", 0)).isEmpty();
    }

    @Test
    void leavesRecentRowsForTheKafkaFastPath() {
        storeOutboundNotification("v1:old", "20000001", "ACSC", "old");
        storeRecentOutboundNotification("v1:recent", "20000001", "ACSC", "recent");

        assertThat(repository.findUnindexedAfter("", 1_000))
                .extracting(IncomingNotification::communicationId)
                .containsExactly("v1:old");
    }

    private void storeOutboundNotification(
            String communicationId,
            String recipientIspb,
            String status,
            String payload
    ) {
        jdbcTemplate.update("""
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
                VALUES (?, ?, 'SETTLED_NOTIFICATION', ?, ?, 'v1', ?, CURRENT_TIMESTAMP - INTERVAL '2 minutes')
                """,
                communicationId,
                recipientIspb,
                "E2E-" + communicationId,
                status,
                bytes(payload)
        );
    }

    private void storeRecentOutboundNotification(
            String communicationId,
            String recipientIspb,
            String status,
            String payload
    ) {
        jdbcTemplate.update("""
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
                VALUES (?, ?, 'SETTLED_NOTIFICATION', ?, ?, 'v1', ?, CURRENT_TIMESTAMP)
                """,
                communicationId,
                recipientIspb,
                "E2E-" + communicationId,
                status,
                bytes(payload)
        );
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
