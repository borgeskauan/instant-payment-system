package br.kauan.notificationgateway.delivery;

import br.kauan.notificationgateway.kafka.NotificationKafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import java.util.List;

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
        NotificationKafkaConsumer consumer = new NotificationKafkaConsumer(repository);

        consumer.consume(List.of(notificationRecord(10L), notificationRecord(11L)));

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
        )).isEqualTo(DeliveryStatus.PENDING.name());
    }

    @Test
    void oneKafkaPollPersistsEachDistinctNotification() {
        NotificationKafkaConsumer consumer = new NotificationKafkaConsumer(repository);

        consumer.consume(List.of(
                notificationRecord(10L, "v1:first", "E2E-1"),
                notificationRecord(11L, "v1:second", "E2E-2")
        ));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_delivery",
                Integer.class
        )).isEqualTo(2);
    }

    @Test
    void invalidNotificationRollsBackTheCompletePoll() {
        IncomingNotification valid = incomingNotification("v1:valid", "20000001", "v1");
        IncomingNotification invalid = incomingNotification("v1:invalid", "20000001", null);

        assertThatThrownBy(() -> repository.saveAllIfAbsent(List.of(valid, invalid)))
                .isInstanceOf(DataAccessException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_delivery WHERE communication_id = ?",
                Integer.class,
                valid.communicationId()
        )).isZero();
    }

    @Test
    void bulkAcknowledgementUpdatesOnlyMatchingAuthenticatedPairs() {
        repository.saveAllIfAbsent(List.of(
                incomingDelivery("v1:first", "20000001"),
                incomingDelivery("v1:second", "20000002"),
                incomingDelivery("v1:third", "20000003")
        ));

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
        repository.saveAllIfAbsent(List.of(incomingDelivery("v1:first", "20000001")));
        List<Acknowledgement> batch =
                List.of(new Acknowledgement("v1:first", "20000001"));

        assertThat(repository.acknowledgeAll(batch)).isOne();
        assertThat(repository.acknowledgeAll(batch)).isZero();
        assertThat(deliveryStatus("v1:first")).isEqualTo("ACKED");
    }

    private ConsumerRecord<String, byte[]> notificationRecord(long offset) {
        return notificationRecord(offset, COMMUNICATION_ID, "E2E-1");
    }

    private ConsumerRecord<String, byte[]> notificationRecord(
            long offset,
            String communicationId,
            String paymentId
    ) {
        ConsumerRecord<String, byte[]> record = new ConsumerRecord<>(
                "psp-notifications",
                0,
                offset,
                "20000001",
                PAYLOAD
        );
        record.headers().add("notification.communication-id", bytes(communicationId));
        record.headers().add("notification.event-type", bytes("SETTLED_NOTIFICATION"));
        record.headers().add("notification.payment-id", bytes(paymentId));
        record.headers().add("notification.status", bytes("ACSC"));
        record.headers().add("notification.schema-version", bytes("v1"));
        return record;
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

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
