package br.kauan.spi.adapter.output.notification;

import br.kauan.spi.adapter.output.kafka.NotificationPublication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class OutboundNotificationRepositoryIntegrationTest {

    private static final String PAYMENT_PREFIX = "E2E-OUTBOUND-REPOSITORY-";

    @Autowired
    private OutboundNotificationRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    @AfterEach
    void cleanFixtureRows() {
        jdbcTemplate.update("DELETE FROM notification_outbox WHERE communication_id LIKE ?", PAYMENT_PREFIX + "%");
    }

    @Test
    void insertsNotificationsInBulkAndRejectsADuplicateMessageId() {
        NotificationPublication first = notification(PAYMENT_PREFIX + "1", "original");
        NotificationPublication second = notification(PAYMENT_PREFIX + "2", "second");

        repository.insertAll(List.of(first, second));

        assertThatThrownBy(() -> repository.insertAll(List.of(
                notification(PAYMENT_PREFIX + "1", "replayed")
        ))).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_outbox WHERE communication_id LIKE ?",
                Integer.class,
                PAYMENT_PREFIX + "%"
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT payload FROM notification_outbox WHERE communication_id = ?",
                byte[].class,
                first.communicationId()
        )).isEqualTo(bytes("original"));
    }

    @Test
    void readsOldestRowsAndDeletesTheExactBatch() throws InterruptedException {
        NotificationPublication first = notification(PAYMENT_PREFIX + "ORDER-1", "first");
        NotificationPublication second = notification(PAYMENT_PREFIX + "ORDER-2", "second");
        NotificationPublication third = notification(PAYMENT_PREFIX + "ORDER-3", "third");
        repository.insertAll(List.of(first));
        Thread.sleep(5);
        repository.insertAll(List.of(second, third));

        assertThat(repository.findOldest(2))
                .extracting(NotificationPublication::communicationId)
                .containsExactly(first.communicationId(), second.communicationId());

        repository.deleteAll(List.of(first.communicationId(), second.communicationId()));

        assertThat(repository.findOldest(10))
                .extracting(NotificationPublication::communicationId)
                .containsExactly(third.communicationId());
    }

    private NotificationPublication notification(String paymentId, String payload) {
        return NotificationPublication.create(
                "20000001",
                bytes(payload),
                paymentId
        );
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
