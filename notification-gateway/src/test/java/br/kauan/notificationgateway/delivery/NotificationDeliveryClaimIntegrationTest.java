package br.kauan.notificationgateway.delivery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(NotificationDeliveryRepository.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17-alpine:///notification_gateway_claim_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
        "spring.flyway.table=notification_gateway_flyway_schema_history",
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.baseline-version=0"
})
@org.springframework.transaction.annotation.Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext
class NotificationDeliveryClaimIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    private static final String CONNECTED_ISPB = "20000001";
    private static final String DISCONNECTED_ISPB = "20000002";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NamedParameterJdbcTemplate namedJdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private NotificationDeliveryRepository repository;

    @BeforeEach
    void resetDeliveries() {
        jdbcTemplate.update("TRUNCATE notification_delivery");
        repository = new NotificationDeliveryRepository(
                namedJdbcTemplate,
                transactionTemplate,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void expiredInFlightDeliveryIsEligibleFromNextAttemptAtAlone() {
        save("v1:expired", CONNECTED_ISPB);
        setState("v1:expired", "IN_FLIGHT", NOW.minusSeconds(1));

        List<NotificationDelivery> claimed = repository.claimForLocalIspbs(
                Set.of(CONNECTED_ISPB),
                10,
                LEASE_DURATION
        );

        assertThat(claimed).extracting(NotificationDelivery::communicationId)
                .containsExactly("v1:expired");
    }

    @Test
    void directDeliveryBecomesRecoverableOnlyAfterItsLeaseExpires() {
        repository.saveAllIfAbsent(
                List.of(new IncomingNotification(
                        "v1:direct-crash",
                        CONNECTED_ISPB,
                        "SETTLED_NOTIFICATION",
                        "E2E-direct-crash",
                        "ACSC",
                        "v1",
                        "{}".getBytes(StandardCharsets.UTF_8)
                )),
                Set.of(CONNECTED_ISPB),
                LEASE_DURATION
        );

        assertThat(repository.claimForLocalIspbs(
                Set.of(CONNECTED_ISPB),
                10,
                LEASE_DURATION
        )).isEmpty();

        NotificationDeliveryRepository afterLease = new NotificationDeliveryRepository(
                namedJdbcTemplate,
                transactionTemplate,
                Clock.fixed(NOW.plus(LEASE_DURATION).plusMillis(1), ZoneOffset.UTC)
        );
        assertThat(afterLease.claimForLocalIspbs(
                Set.of(CONNECTED_ISPB),
                10,
                LEASE_DURATION
        )).extracting(NotificationDelivery::communicationId)
                .containsExactly("v1:direct-crash");
    }

    @Test
    void claimsOnlyDueNonAckedDeliveriesForConnectedRecipients() {
        save("v1:pending", CONNECTED_ISPB);
        save("v1:retry", CONNECTED_ISPB);
        save("v1:future", CONNECTED_ISPB);
        save("v1:acked", CONNECTED_ISPB);
        save("v1:disconnected", DISCONNECTED_ISPB);
        setState("v1:pending", "PENDING", NOW.minusSeconds(30));
        setState("v1:retry", "RETRYABLE_FAILED", NOW.minusSeconds(20));
        setState("v1:future", "PENDING", NOW.plusSeconds(1));
        setState("v1:acked", "ACKED", NOW.minusSeconds(40));
        setState("v1:disconnected", "PENDING", NOW.minusSeconds(50));

        List<NotificationDelivery> claimed = repository.claimForLocalIspbs(
                Set.of(CONNECTED_ISPB),
                10,
                LEASE_DURATION
        );

        assertThat(claimed).extracting(NotificationDelivery::communicationId)
                .containsExactlyInAnyOrder("v1:pending", "v1:retry");
    }

    @Test
    void limitSelectsOldestDueDeliveriesAndClaimWritesTheLeaseDeadline() {
        save("v1:newer", CONNECTED_ISPB);
        save("v1:oldest-b", CONNECTED_ISPB);
        save("v1:oldest-a", CONNECTED_ISPB);
        setState("v1:newer", "PENDING", NOW.minusSeconds(10));
        setState("v1:oldest-b", "PENDING", NOW.minusSeconds(20));
        setState("v1:oldest-a", "PENDING", NOW.minusSeconds(20));

        List<NotificationDelivery> claimed = repository.claimForLocalIspbs(
                Set.of(CONNECTED_ISPB),
                2,
                LEASE_DURATION
        );

        assertThat(claimed).extracting(NotificationDelivery::communicationId)
                .containsExactlyInAnyOrder("v1:oldest-a", "v1:oldest-b");
        assertThat(state("v1:oldest-a")).isEqualTo("IN_FLIGHT");
        assertThat(nextAttemptAt("v1:oldest-a")).isEqualTo(at(NOW.plus(LEASE_DURATION)));
        assertThat(state("v1:newer")).isEqualTo("PENDING");
    }

    @Test
    void concurrentClaimsAcquireEachDeliveryAtMostOnce() throws Exception {
        save("v1:single", CONNECTED_ISPB);
        setState("v1:single", "PENDING", NOW.minusSeconds(1));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<List<NotificationDelivery>> first = executor.submit(
                    () -> claimAfterBarrier(ready, start)
            );
            Future<List<NotificationDelivery>> second = executor.submit(
                    () -> claimAfterBarrier(ready, start)
            );
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .flatExtracting(list -> list)
                    .extracting(NotificationDelivery::communicationId)
                    .containsExactly("v1:single");
        }
    }

    @Test
    void migrationUsesTwoActiveDeliveryIndexesWithoutLeaseColumn() {
        Integer leaseColumnCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'notification_delivery'
                  AND column_name = 'lease_until'
                """, Integer.class);
        List<String> indexes = jdbcTemplate.queryForList("""
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'notification_delivery'
                  AND indexname IN (
                    'notification_delivery_claim_due_idx',
                    'notification_delivery_claim_recipient_due_idx'
                  )
                ORDER BY indexname
                """, String.class);

        assertThat(leaseColumnCount).isZero();
        assertThat(indexes).hasSize(2).allSatisfy(index -> {
            assertThat(index).contains("next_attempt_at");
            assertThat(index).contains("delivery_status");
            assertThat(index).contains("PENDING", "RETRYABLE_FAILED", "IN_FLIGHT");
            assertThat(index).doesNotContain("ACKED", "communication_id");
        });
        assertThat(indexes).anySatisfy(index ->
                assertThat(index).contains("recipient_ispb", "next_attempt_at"));
    }

    private List<NotificationDelivery> claimAfterBarrier(
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        return repository.claimForLocalIspbs(
                Set.of(CONNECTED_ISPB),
                1,
                LEASE_DURATION
        );
    }

    private void save(String communicationId, String recipientIspb) {
        repository.saveAllIfAbsent(List.of(new IncomingNotification(
                communicationId,
                recipientIspb,
                "SETTLED_NOTIFICATION",
                "E2E-" + communicationId,
                "ACSC",
                "v1",
                "{}".getBytes(StandardCharsets.UTF_8)
        )), Set.of(), LEASE_DURATION);
    }

    private void setState(String communicationId, String status, Instant nextAttemptAt) {
        jdbcTemplate.update("""
                UPDATE notification_delivery
                SET delivery_status = ?, next_attempt_at = ?
                WHERE communication_id = ?
                """, status, at(nextAttemptAt), communicationId);
    }

    private String state(String communicationId) {
        return jdbcTemplate.queryForObject("""
                SELECT delivery_status
                FROM notification_delivery
                WHERE communication_id = ?
                """, String.class, communicationId);
    }

    private OffsetDateTime nextAttemptAt(String communicationId) {
        return jdbcTemplate.queryForObject("""
                SELECT next_attempt_at
                FROM notification_delivery
                WHERE communication_id = ?
                """, OffsetDateTime.class, communicationId);
    }

    private OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
