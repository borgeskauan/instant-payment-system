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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(NotificationDeliveryRepository.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17-alpine:///notification_gateway_pull_repository_test",
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

    private static final byte[] PAYLOAD = "{\"status\":\"ACSC\"}".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private NotificationDeliveryRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void truncateDeliveries() {
        jdbcTemplate.update("TRUNCATE notification_delivery RESTART IDENTITY");
    }

    @Test
    void persistsOneLogicalDeliveryAndReturnsOnlyRecipientsWithNewRows() {
        assertThat(repository.saveAllIfAbsent(List.of(
                incoming("v1:first", "20000001"),
                incoming("v1:second", "20000002")
        ))).containsExactlyInAnyOrder("20000001", "20000002");

        assertThat(repository.saveAllIfAbsent(List.of(
                incoming("v1:first", "20000001")
        ))).isEmpty();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_delivery", Integer.class
        )).isEqualTo(2);
    }

    @Test
    void readsOnlyTheAuthenticatedPspAfterItsCursorInStablePositionOrder() {
        repository.saveAllIfAbsent(List.of(
                incoming("v1:first", "20000001"),
                incoming("v1:other", "20000002"),
                incoming("v1:second", "20000001")
        ));

        List<NotificationDelivery> firstPage = repository.findAfter("20000001", 0, 1);
        List<NotificationDelivery> secondPage = repository.findAfter(
                "20000001", firstPage.getFirst().deliveryPosition(), 10
        );

        assertThat(firstPage).extracting(NotificationDelivery::communicationId)
                .containsExactly("v1:first");
        assertThat(secondPage).extracting(NotificationDelivery::communicationId)
                .containsExactly("v1:second");
        assertThat(secondPage.getFirst().deliveryPosition())
                .isGreaterThan(firstPage.getFirst().deliveryPosition());
    }

    @Test
    void invalidNotificationRollsBackTheCompleteKafkaBatch() {
        IncomingNotification valid = incoming("v1:valid", "20000001");
        IncomingNotification invalid = new IncomingNotification(
                "v1:invalid", "20000001", "SETTLED_NOTIFICATION", "E2E-1", "ACSC", null, PAYLOAD
        );

        assertThatThrownBy(() -> repository.saveAllIfAbsent(List.of(valid, invalid)))
                .isInstanceOf(DataAccessException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_delivery", Integer.class
        )).isZero();
    }

    private IncomingNotification incoming(String communicationId, String recipientIspb) {
        return new IncomingNotification(
                communicationId,
                recipientIspb,
                "SETTLED_NOTIFICATION",
                "E2E-1",
                "ACSC",
                "v1",
                PAYLOAD
        );
    }
}
