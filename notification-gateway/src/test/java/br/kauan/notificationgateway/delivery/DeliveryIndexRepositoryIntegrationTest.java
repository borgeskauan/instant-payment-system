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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DeliveryIndexRepository.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17-alpine:///delivery_index_repository_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
        "spring.flyway.table=notification_gateway_flyway_schema_history",
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.baseline-version=0"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext
class DeliveryIndexRepositoryIntegrationTest {

    @Autowired
    private DeliveryIndexRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetTables() {
        jdbcTemplate.update("TRUNCATE delivery_index");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS outbound_notification (
                    communication_id TEXT PRIMARY KEY,
                    payload BYTEA NOT NULL
                )
                """);
        jdbcTemplate.update("TRUNCATE outbound_notification");
    }

    @Test
    void assignsConsecutivePositionsPerPspInKafkaSourceOrder() {
        List<NotificationDelivery> indexed = repository.indexNew(List.of(
                incoming("v1:first", "20000001", "first"),
                incoming("v1:other", "20000002", "other"),
                incoming("v1:second", "20000001", "second")
        ));

        assertThat(indexed).extracting(
                NotificationDelivery::communicationId,
                NotificationDelivery::recipientIspb,
                NotificationDelivery::deliveryPosition
        ).containsExactly(
                tuple("v1:first", "20000001", 1L),
                tuple("v1:other", "20000002", 1L),
                tuple("v1:second", "20000001", 2L)
        );
    }

    @Test
    void replayFallsBackWithoutCreatingAnIndexOrConsumingAPosition() {
        repository.indexNew(List.of(
                incoming("v1:first", "20000001", "first"),
                incoming("v1:second", "20000001", "second")
        ));

        List<NotificationDelivery> indexed = repository.indexNew(List.of(
                incoming("v1:first", "20000001", "first"),
                incoming("v1:third", "20000001", "third")
        ));

        assertThat(indexed).extracting(
                NotificationDelivery::communicationId,
                NotificationDelivery::deliveryPosition
        ).containsExactly(tuple("v1:third", 3L));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_index", Integer.class
        )).isEqualTo(3);
        assertThat(jdbcTemplate.queryForList("""
                SELECT delivery_position
                FROM delivery_index
                WHERE recipient_ispb = '20000001'
                ORDER BY delivery_position
                """, Long.class)).containsExactly(1L, 2L, 3L);
    }

    @Test
    void rejectsACommunicationIdAlreadyOwnedByAnotherRecipient() {
        repository.indexNew(List.of(incoming("v1:first", "20000001", "first")));

        assertThatThrownBy(() -> repository.indexNew(List.of(
                incoming("v1:first", "20000002", "first")
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("v1:first")
                .hasMessageContaining("20000001")
                .hasMessageContaining("20000002");
    }

    @Test
    void divergentReplayRollsBackNewIndexesFromTheSameBatch() {
        repository.indexNew(List.of(incoming("v1:first", "20000001", "first")));

        assertThatThrownBy(() -> repository.indexNew(List.of(
                incoming("v1:new", "20000002", "new"),
                incoming("v1:first", "20000002", "divergent")
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("v1:first")
                .hasMessageContaining("20000001")
                .hasMessageContaining("20000002");

        assertThat(jdbcTemplate.queryForList(
                "SELECT communication_id FROM delivery_index ORDER BY communication_id",
                String.class
        )).containsExactly("v1:first");
    }

    @Test
    void invalidPayloadPreventsTheCompleteKafkaBatchFromBeingIndexed() {
        IncomingNotification invalid = new IncomingNotification(
                "v1:invalid",
                "20000001",
                null
        );

        assertThatThrownBy(() -> repository.indexNew(List.of(
                incoming("v1:valid", "20000001", "valid"),
                invalid
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("notification payload is required");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_index", Integer.class
        )).isZero();
    }

    @Test
    void fallbackReadsCanonicalPayloadAfterThePspCursor() {
        repository.indexNew(List.of(
                incoming("v1:first", "20000001", "kafka-first"),
                incoming("v1:other", "20000002", "kafka-other"),
                incoming("v1:second", "20000001", "kafka-second")
        ));
        storeOutboundNotification("v1:first", "canonical-first");
        storeOutboundNotification("v1:other", "canonical-other");
        storeOutboundNotification("v1:second", "canonical-second");

        List<NotificationDelivery> firstPage = repository.findAfter("20000001", 0, 1);
        List<NotificationDelivery> secondPage = repository.findAfter(
                "20000001", firstPage.getFirst().deliveryPosition(), 15
        );

        assertThat(firstPage).singleElement().satisfies(delivery -> {
            assertThat(delivery.communicationId()).isEqualTo("v1:first");
            assertThat(delivery.payload()).isEqualTo(bytes("canonical-first"));
        });
        assertThat(secondPage).singleElement().satisfies(delivery -> {
            assertThat(delivery.communicationId()).isEqualTo("v1:second");
            assertThat(delivery.payload()).isEqualTo(bytes("canonical-second"));
        });
    }

    @Test
    void concurrentBatchesForTheSamePspProduceOneGaplessSequence() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                List<Future<List<NotificationDelivery>>> futures = List.of(
                        executor.submit(() -> {
                            start.await();
                            return repository.indexNew(List.of(
                                    incoming("v1:first", "20000001", "first"),
                                    incoming("v1:second", "20000001", "second")
                            ));
                        }),
                        executor.submit(() -> {
                            start.await();
                            return repository.indexNew(List.of(
                                    incoming("v1:third", "20000001", "third"),
                                    incoming("v1:fourth", "20000001", "fourth")
                            ));
                        })
                );
                start.countDown();

                List<NotificationDelivery> indexed = new ArrayList<>();
                for (Future<List<NotificationDelivery>> future : futures) {
                    indexed.addAll(future.get());
                }
                assertThat(indexed).hasSize(4);
            }

            assertThat(jdbcTemplate.queryForList("""
                    SELECT delivery_position
                    FROM delivery_index
                    WHERE recipient_ispb = '20000001'
                    ORDER BY delivery_position
                    """, Long.class)).containsExactly(1L, 2L, 3L, 4L);
        });
    }

    @Test
    void concurrentIdenticalDeliveriesProduceOneIndexAndConsumeOnePosition() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                List<Future<List<NotificationDelivery>>> futures = List.of(
                        executor.submit(() -> {
                            start.await();
                            return repository.indexNew(List.of(
                                    incoming("v1:replay", "20000001", "same")
                            ));
                        }),
                        executor.submit(() -> {
                            start.await();
                            return repository.indexNew(List.of(
                                    incoming("v1:replay", "20000001", "same")
                            ));
                        })
                );
                start.countDown();

                assertThat(futures.get(0).get().size() + futures.get(1).get().size()).isOne();
            }

            assertThat(jdbcTemplate.queryForList("""
                    SELECT communication_id, delivery_position
                    FROM delivery_index
                    WHERE recipient_ispb = '20000001'
                    """))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.get("communication_id")).isEqualTo("v1:replay");
                        assertThat(row.get("delivery_position")).isEqualTo(1L);
                    });
        });
    }

    private void storeOutboundNotification(String communicationId, String payload) {
        jdbcTemplate.update(
                "INSERT INTO outbound_notification (communication_id, payload) VALUES (?, ?)",
                communicationId,
                bytes(payload)
        );
    }

    private IncomingNotification incoming(String communicationId, String recipientIspb, String payload) {
        return new IncomingNotification(
                communicationId,
                recipientIspb,
                bytes(payload)
        );
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
