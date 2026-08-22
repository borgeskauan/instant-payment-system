package br.kauan.notificationgateway.delivery;

import br.kauan.notificationgateway.grpc.PullRequestCoordinator;
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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        DeliveryIndexRepository.class,
        NotificationReconciliationRepository.class,
        RecentNotificationBuffer.class,
        PullRequestCoordinator.class,
        NotificationIndexingService.class,
        NotificationDeliveryReader.class
})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17-alpine:///notification_reconciliation_integration_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
        "spring.flyway.table=notification_gateway_flyway_schema_history",
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.baseline-version=0"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext
class NotificationReconciliationIntegrationTest {

    @Autowired
    private DeliveryIndexRepository deliveryIndexRepository;

    @Autowired
    private NotificationReconciliationRepository reconciliationRepository;

    @Autowired
    private NotificationIndexingService indexingService;

    @Autowired
    private NotificationDeliveryReader reader;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetTables() {
        jdbcTemplate.update("TRUNCATE delivery_index");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS outbound_notification (
                    communication_id TEXT PRIMARY KEY,
                    recipient_ispb TEXT NOT NULL,
                    payload BYTEA NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.update("TRUNCATE outbound_notification");
    }

    @Test
    void durableNotificationIsDeliveredWithoutAnyKafkaPublication() {
        IncomingNotification notification = storeOutboundNotification(
                "v1:without-kafka",
                "20000001",
                "canonical-payload"
        );

        reconciler().reconcile();

        assertThat(reader.findAfter("20000001", 0, 15))
                .singleElement()
                .satisfies(delivery -> {
                    assertThat(delivery.communicationId()).isEqualTo(notification.communicationId());
                    assertThat(delivery.deliveryPosition()).isOne();
                    assertThat(delivery.payload()).isEqualTo(notification.payload());
                });
    }

    @Test
    void aNewReconcilerRecoversOutboxRowsAfterRestartWithEmptyMemory() {
        IncomingNotification notification = storeOutboundNotification(
                "v1:after-restart",
                "20000001",
                "restart-payload"
        );

        reconciler().reconcile();
        NotificationDeliveryReader restartedReader = new NotificationDeliveryReader(
                new RecentNotificationBuffer(),
                deliveryIndexRepository
        );

        assertThat(restartedReader.findAfter("20000001", 0, 15))
                .singleElement()
                .satisfies(delivery -> {
                    assertThat(delivery.communicationId()).isEqualTo(notification.communicationId());
                    assertThat(delivery.payload()).isEqualTo(notification.payload());
                });
    }

    @Test
    void kafkaFastPathAndReconcilerCreateOneLogicalPosition() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            IncomingNotification notification = storeOutboundNotification(
                    "v1:concurrent",
                    "20000001",
                    "same-payload"
            );
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var kafka = executor.submit(() -> {
                    start.await();
                    indexingService.ensureIndexed(List.of(notification));
                    return null;
                });
                var reconciliation = executor.submit(() -> {
                    start.await();
                    reconciler().reconcile();
                    return null;
                });
                start.countDown();
                kafka.get();
                reconciliation.get();
            }

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM delivery_index WHERE communication_id = 'v1:concurrent'",
                    Integer.class
            )).isOne();
            assertThat(jdbcTemplate.queryForList(
                    "SELECT delivery_position FROM delivery_index WHERE recipient_ispb = '20000001'",
                    Long.class
            )).containsExactly(1L);
        });
    }

    private NotificationReconciler reconciler() {
        return new NotificationReconciler(reconciliationRepository, indexingService, 1_000);
    }

    private IncomingNotification storeOutboundNotification(
            String communicationId,
            String recipientIspb,
            String payload
    ) {
        IncomingNotification notification = new IncomingNotification(
                communicationId,
                recipientIspb,
                payload.getBytes(StandardCharsets.UTF_8)
        );
        jdbcTemplate.update("""
                INSERT INTO outbound_notification (
                    communication_id,
                    recipient_ispb,
                    payload,
                    created_at
                )
                VALUES (?, ?, ?, CURRENT_TIMESTAMP - INTERVAL '2 minutes')
                """,
                notification.communicationId(),
                notification.recipientIspb(),
                notification.payload()
        );
        return notification;
    }
}
