package br.kauan.spi.adapter.output.outbox;

import br.kauan.spi.adapter.output.kafka.NotificationPublication;
import br.kauan.spi.adapter.output.kafka.NotificationPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@Transactional
class NotificationOutboxRepositoryIntegrationTest {

    @MockitoBean
    private NotificationOutboxWorker notificationOutboxWorker;

    @Autowired
    private NotificationOutboxRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanFixtureRows() {
        jdbcTemplate.update("DELETE FROM notification_outbox WHERE payment_id LIKE 'E2E-OUTBOX-%'");
    }

    @Test
    void insertsNotificationsInBulkAndKeepsTheOriginalBytesOnReplay() {
        NotificationPublication first = notification("E2E-OUTBOX-1", "original");
        NotificationPublication second = notification("E2E-OUTBOX-2", "second");

        repository.insertAll(List.of(first, second));
        repository.insertAll(List.of(notification("E2E-OUTBOX-1", "replayed")));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_outbox WHERE payment_id LIKE 'E2E-OUTBOX-%'",
                Integer.class
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT payload FROM notification_outbox WHERE communication_id = ?",
                byte[].class,
                first.communicationId()
        )).isEqualTo("original".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void concurrentSelectionsMaySeeTheSamePendingRowAndPublishedIsTerminal() {
        NotificationPublication notification = notification("E2E-OUTBOX-CONCURRENT", "payload");
        repository.insertAll(List.of(notification));

        List<NotificationPublication> firstWorkerSelection = repository.findPending(1_000);
        List<NotificationPublication> secondWorkerSelection = repository.findPending(1_000);

        assertThat(firstWorkerSelection).extracting(NotificationPublication::communicationId)
                .contains(notification.communicationId());
        assertThat(secondWorkerSelection).extracting(NotificationPublication::communicationId)
                .contains(notification.communicationId());
        assertThat(repository.markPublished(List.of(notification.communicationId()))).isEqualTo(1);
        assertThat(repository.scheduleRetry(
                List.of(new NotificationPublicationFailure(notification.communicationId(), "late failure")),
                Duration.ofSeconds(1)
        )).isZero();
        assertThat(repository.markPublished(List.of(notification.communicationId()))).isZero();

        OutboxState state = outboxState(notification.communicationId());
        assertThat(state).isEqualTo(new OutboxState("PUBLISHED", 1, null, true));
    }

    @Test
    void failedPublicationRemainsPendingAndSchedulesFixedRetry() {
        NotificationPublication notification = notification("E2E-OUTBOX-RETRY", "payload");
        repository.insertAll(List.of(notification));

        assertThat(repository.scheduleRetry(
                List.of(new NotificationPublicationFailure(notification.communicationId(), "broker unavailable")),
                Duration.ofSeconds(1)
        )).isEqualTo(1);

        OutboxState state = outboxState(notification.communicationId());
        assertThat(state).isEqualTo(new OutboxState("PENDING", 1, "broker unavailable", false));
        assertThat(jdbcTemplate.queryForObject(
                """
                        SELECT EXTRACT(EPOCH FROM (next_attempt_at - CURRENT_TIMESTAMP))
                        FROM notification_outbox
                        WHERE communication_id = ?
                        """,
                Double.class,
                notification.communicationId()
        )).isCloseTo(1.0, within(0.001));
    }

    @Test
    void newWorkerPublishesPendingRowLeftByPreviousWorker() {
        NotificationPublication notification = notification("E2E-OUTBOX-RESTART", "durable-payload");
        repository.insertAll(List.of(notification));

        NotificationPublisher unavailablePublisher = mock(NotificationPublisher.class);
        when(unavailablePublisher.publish(any(NotificationPublication.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        new NotificationOutboxWorker(repository, unavailablePublisher, 1_000, Duration.ZERO)
                .publishPending();

        assertThat(outboxState(notification.communicationId())).isEqualTo(new OutboxState(
                "PENDING",
                1,
                "java.lang.IllegalStateException: broker unavailable",
                false
        ));

        NotificationPublisher recoveredPublisher = mock(NotificationPublisher.class);
        SendResult<String, byte[]> sendResult = mock(SendResult.class);
        when(recoveredPublisher.publish(any(NotificationPublication.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));
        new NotificationOutboxWorker(repository, recoveredPublisher, 1_000, Duration.ZERO)
                .publishPending();

        ArgumentCaptor<NotificationPublication> publication = ArgumentCaptor.forClass(NotificationPublication.class);
        verify(recoveredPublisher).publish(publication.capture());
        assertThat(publication.getValue().communicationId()).isEqualTo(notification.communicationId());
        assertThat(publication.getValue().payload()).isEqualTo("durable-payload".getBytes(StandardCharsets.UTF_8));
        assertThat(outboxState(notification.communicationId()))
                .isEqualTo(new OutboxState("PUBLISHED", 2, null, true));
    }

    private OutboxState outboxState(String communicationId) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT publication_status, attempt_count, last_error, published_at IS NOT NULL
                        FROM notification_outbox
                        WHERE communication_id = ?
                        """,
                (resultSet, rowNumber) -> new OutboxState(
                        resultSet.getString(1),
                        resultSet.getInt(2),
                        resultSet.getString(3),
                        resultSet.getBoolean(4)
                ),
                communicationId
        );
    }

    private NotificationPublication notification(String paymentId, String payload) {
        return NotificationPublication.create(
                "20000001",
                payload.getBytes(StandardCharsets.UTF_8),
                "ACCEPTANCE_REQUEST",
                paymentId,
                null
        );
    }

    private record OutboxState(
            String publicationStatus,
            int attemptCount,
            String lastError,
            boolean publishedAtPresent
    ) {
    }
}
