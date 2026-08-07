package br.kauan.notificationgateway.delivery;

import br.kauan.notificationgateway.kafka.NotificationKafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void duplicateKafkaPublicationsCreateOneLogicalDelivery() {
        NotificationKafkaConsumer consumer = new NotificationKafkaConsumer(repository);

        consumer.consume(notificationRecord(10L));
        consumer.consume(notificationRecord(11L));

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

    private ConsumerRecord<String, byte[]> notificationRecord(long offset) {
        ConsumerRecord<String, byte[]> record = new ConsumerRecord<>(
                "psp-notifications",
                0,
                offset,
                "20000001",
                PAYLOAD
        );
        record.headers().add("notification.communication-id", bytes(COMMUNICATION_ID));
        record.headers().add("notification.event-type", bytes("SETTLED_NOTIFICATION"));
        record.headers().add("notification.payment-id", bytes("E2E-1"));
        record.headers().add("notification.status", bytes("ACSC"));
        record.headers().add("notification.schema-version", bytes("v1"));
        return record;
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
