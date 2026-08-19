package br.kauan.notificationgateway.delivery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(NotificationDeliveryRepository.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17-alpine:///notification_gateway_test",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
        "spring.flyway.table=notification_gateway_flyway_schema_history",
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.baseline-version=0"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext
class NotificationDeliveryDeduplicationIntegrationTest {

    private static final String COMMUNICATION_ID = "v1:deduplication-integration";
    private static final byte[] PAYLOAD = "{\"status\":\"ACSC\"}".getBytes(StandardCharsets.UTF_8);
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);

    @Autowired
    private NotificationDeliveryRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void truncateDeliveries() {
        jdbcTemplate.update("TRUNCATE notification_delivery");
    }

    @Test
    void duplicateKafkaPublicationsCreateOneLogicalDelivery() {
        List<NotificationDelivery> firstDispatch = repository.saveAllIfAbsent(
                List.of(incomingDelivery(COMMUNICATION_ID, "20000001")),
                Set.of("20000001"),
                LEASE_DURATION
        );
        List<NotificationDelivery> replayDispatch = repository.saveAllIfAbsent(
                List.of(incomingDelivery(COMMUNICATION_ID, "20000001")),
                Set.of("20000001"),
                LEASE_DURATION
        );

        Integer deliveryCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_delivery WHERE communication_id = ?",
                Integer.class,
                COMMUNICATION_ID
        );

        assertThat(deliveryCount).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT payload FROM notification_delivery WHERE communication_id = ?",
                byte[].class,
                COMMUNICATION_ID
        )).isEqualTo(PAYLOAD);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT delivery_status FROM notification_delivery WHERE communication_id = ?",
                String.class,
                COMMUNICATION_ID
        )).isEqualTo(DeliveryStatus.IN_FLIGHT.name());
        assertThat(firstDispatch).extracting(NotificationDelivery::communicationId)
                .containsExactly(COMMUNICATION_ID);
        assertThat(replayDispatch).isEmpty();
    }

    @Test
    void connectedRecipientsAreLeasedForDirectDispatchWhileDisconnectedRecipientsRemainPending() {
        List<NotificationDelivery> directDispatches = repository.saveAllIfAbsent(
                List.of(
                        incomingDelivery("v1:connected", "20000001"),
                        incomingDelivery("v1:disconnected", "20000002")
                ),
                Set.of("20000001"),
                LEASE_DURATION
        );

        assertThat(directDispatches)
                .extracting(NotificationDelivery::communicationId, NotificationDelivery::recipientIspb)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("v1:connected", "20000001"));
        assertThat(deliveryStatus("v1:connected")).isEqualTo("IN_FLIGHT");
        assertThat(attemptCount("v1:connected")).isOne();
        assertThat(deliveryStatus("v1:disconnected")).isEqualTo("PENDING");
        assertThat(attemptCount("v1:disconnected")).isZero();
    }

    @Test
    void invalidNotificationRollsBackTheCompletePoll() {
        IncomingNotification valid = incomingNotification("v1:valid", "20000001", "v1");
        IncomingNotification invalid = incomingNotification("v1:invalid", "20000001", null);

        assertThatThrownBy(() -> repository.saveAllIfAbsent(
                List.of(valid, invalid),
                Set.of("20000001"),
                LEASE_DURATION
        ))
                .isInstanceOf(DataAccessException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_delivery WHERE communication_id = ?",
                Integer.class,
                valid.communicationId()
        )).isZero();
    }

    @Test
    void bulkAcknowledgementUpdatesOnlyMatchingAuthenticatedPairs() {
        savePending(
                incomingDelivery("v1:first", "20000001"),
                incomingDelivery("v1:second", "20000002"),
                incomingDelivery("v1:third", "20000003")
        );

        int updated = repository.acknowledgeAll(List.of(
                new Acknowledgement("v1:first", "20000001"),
                new Acknowledgement("v1:second", "99999999"),
                new Acknowledgement("v1:unknown", "20000003")
        ));

        assertThat(updated).isOne();
        assertThat(deliveryStatus("v1:first")).isEqualTo("ACKED");
        assertThat(deliveryStatus("v1:second")).isEqualTo("PENDING");
        assertThat(deliveryStatus("v1:third")).isEqualTo("PENDING");
    }

    @Test
    void replayedBulkAcknowledgementDoesNotCreateAnotherTransition() {
        savePending(incomingDelivery("v1:first", "20000001"));
        List<Acknowledgement> batch =
                List.of(new Acknowledgement("v1:first", "20000001"));

        assertThat(repository.acknowledgeAll(batch)).isOne();
        assertThat(repository.acknowledgeAll(batch)).isZero();
        assertThat(deliveryStatus("v1:first")).isEqualTo("ACKED");
    }

    private IncomingNotification incomingDelivery(
            String communicationId,
            String recipientIspb
    ) {
        return incomingNotification(communicationId, recipientIspb, "v1");
    }

    private IncomingNotification incomingNotification(
            String communicationId,
            String recipientIspb,
            String schemaVersion
    ) {
        return new IncomingNotification(
                communicationId,
                recipientIspb,
                "SETTLED_NOTIFICATION",
                "E2E-1",
                "ACSC",
                schemaVersion,
                PAYLOAD
        );
    }

    private String deliveryStatus(String communicationId) {
        return jdbcTemplate.queryForObject(
                "SELECT delivery_status FROM notification_delivery WHERE communication_id = ?",
                String.class,
                communicationId
        );
    }

    private int attemptCount(String communicationId) {
        return jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM notification_delivery WHERE communication_id = ?",
                Integer.class,
                communicationId
        );
    }

    private void savePending(IncomingNotification... notifications) {
        repository.saveAllIfAbsent(List.of(notifications), Set.of(), LEASE_DURATION);
    }

}
