package br.kauan.spi.adapter.output.outbox;

import br.kauan.spi.adapter.output.kafka.NotificationPublication;
import br.kauan.spi.adapter.output.kafka.NotificationPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "spi.notification-outbox.fixed-delay=1h")
class NotificationOutboxFastPathIntegrationTest {

    private static final String PAYMENT_PREFIX = "E2E-OUTBOX-FAST-PATH-";

    @MockitoBean
    private NotificationPublisher notificationPublisher;

    @Autowired
    private NotificationOutboxRepository repository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void preparePublisher() {
        cleanFixtureRows();
        reset(notificationPublisher);
        SendResult<String, byte[]> sendResult = mock(SendResult.class);
        when(notificationPublisher.publish(any(NotificationPublication.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));
    }

    @AfterEach
    void cleanFixtureRows() {
        jdbcTemplate.update("DELETE FROM notification_outbox WHERE payment_id LIKE ?", PAYMENT_PREFIX + "%");
    }

    @Test
    void committedRowsArePublishedOnlyAfterTheBusinessTransactionCommits() {
        NotificationPublication notification = notification(PAYMENT_PREFIX + "COMMIT");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            List<NotificationPublication> inserted = repository.insertAll(List.of(notification));
            eventPublisher.publishEvent(new NotificationOutboxBatchReady(inserted));

            verifyNoInteractions(notificationPublisher);
            assertThat(publicationStatus(notification.communicationId())).isEqualTo("PENDING");
        });

        verify(notificationPublisher).publish(notification);
        assertThat(publicationStatus(notification.communicationId())).isEqualTo("PUBLISHED");
    }

    @Test
    void rolledBackRowsAreNeitherPersistedNorPublished() {
        NotificationPublication notification = notification(PAYMENT_PREFIX + "ROLLBACK");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            List<NotificationPublication> inserted = repository.insertAll(List.of(notification));
            eventPublisher.publishEvent(new NotificationOutboxBatchReady(inserted));
            status.setRollbackOnly();
        });

        verifyNoInteractions(notificationPublisher);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_outbox WHERE communication_id = ?",
                Integer.class,
                notification.communicationId()
        )).isZero();
    }

    @Test
    void failedPostCommitPublicationLeavesTheCommittedRowPendingForRecovery() {
        NotificationPublication notification = notification(PAYMENT_PREFIX + "PUBLICATION-FAILURE");
        when(notificationPublisher.publish(notification))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            List<NotificationPublication> inserted = repository.insertAll(List.of(notification));
            eventPublisher.publishEvent(new NotificationOutboxBatchReady(inserted));
        });

        verify(notificationPublisher).publish(notification);
        assertThat(outboxState(notification.communicationId()))
                .isEqualTo(new OutboxState("PENDING", 1, "java.lang.IllegalStateException: broker unavailable"));
    }

    private String publicationStatus(String communicationId) {
        return jdbcTemplate.queryForObject(
                "SELECT publication_status FROM notification_outbox WHERE communication_id = ?",
                String.class,
                communicationId
        );
    }

    private OutboxState outboxState(String communicationId) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT publication_status, attempt_count, last_error
                        FROM notification_outbox
                        WHERE communication_id = ?
                        """,
                (resultSet, rowNumber) -> new OutboxState(
                        resultSet.getString(1),
                        resultSet.getInt(2),
                        resultSet.getString(3)
                ),
                communicationId
        );
    }

    private NotificationPublication notification(String paymentId) {
        return NotificationPublication.create(
                "20000001",
                paymentId.getBytes(StandardCharsets.UTF_8),
                "ACCEPTANCE_REQUEST",
                paymentId,
                null
        );
    }

    private record OutboxState(String publicationStatus, int attemptCount, String lastError) {
    }
}
