package br.kauan.spi.adapter.output.notification;

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

@SpringBootTest
class OutboundNotificationFastPathIntegrationTest {

    private static final String PAYMENT_PREFIX = "E2E-OUTBOUND-FAST-PATH-";

    @MockitoBean
    private NotificationPublisher notificationPublisher;

    @Autowired
    private OutboundNotificationRepository repository;

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
        jdbcTemplate.update("DELETE FROM outbound_notification WHERE payment_id LIKE ?", PAYMENT_PREFIX + "%");
    }

    @Test
    void committedRowsArePublishedOnlyAfterTheBusinessTransactionCommits() {
        NotificationPublication notification = notification(PAYMENT_PREFIX + "COMMIT");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            repository.insertAll(List.of(notification));
            eventPublisher.publishEvent(new OutboundNotificationBatchReady(List.of(notification)));

            verifyNoInteractions(notificationPublisher);
            assertThat(storedRows(notification.communicationId())).isOne();
        });

        verify(notificationPublisher).publish(notification);
        assertThat(storedRows(notification.communicationId())).isOne();
    }

    @Test
    void rolledBackRowsAreNeitherPersistedNorPublished() {
        NotificationPublication notification = notification(PAYMENT_PREFIX + "ROLLBACK");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            repository.insertAll(List.of(notification));
            eventPublisher.publishEvent(new OutboundNotificationBatchReady(List.of(notification)));
            status.setRollbackOnly();
        });

        verifyNoInteractions(notificationPublisher);
        assertThat(storedRows(notification.communicationId())).isZero();
    }

    @Test
    void failedBestEffortPublicationLeavesTheCommittedRowUnchangedForReconciliation() {
        NotificationPublication notification = notification(PAYMENT_PREFIX + "PUBLICATION-FAILURE");
        when(notificationPublisher.publish(notification))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            repository.insertAll(List.of(notification));
            eventPublisher.publishEvent(new OutboundNotificationBatchReady(List.of(notification)));
        });

        verify(notificationPublisher).publish(notification);
        assertThat(storedRows(notification.communicationId())).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT payload FROM outbound_notification WHERE communication_id = ?",
                byte[].class,
                notification.communicationId()
        )).isEqualTo(notification.payload());
    }

    private int storedRows(String communicationId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbound_notification WHERE communication_id = ?",
                Integer.class,
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
}
