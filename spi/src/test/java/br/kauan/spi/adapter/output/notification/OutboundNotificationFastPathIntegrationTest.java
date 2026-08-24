package br.kauan.spi.adapter.output.notification;

import br.kauan.spi.adapter.output.kafka.NotificationPublication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest
class OutboundNotificationFastPathIntegrationTest {

    private static final String PAYMENT_PREFIX = "E2E-OUTBOUND-FAST-PATH-";

    @MockitoBean
    private NotificationOutboxPipeline pipeline;

    @Autowired
    private OutboundNotificationRepository repository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void prepare() {
        cleanFixtureRows();
        reset(pipeline);
    }

    @AfterEach
    void cleanFixtureRows() {
        jdbcTemplate.update("DELETE FROM notification_outbox WHERE communication_id LIKE ?", PAYMENT_PREFIX + "%");
    }

    @Test
    void committedRowsEnterThePipelineOnlyAfterTheBusinessTransactionCommits() {
        NotificationPublication notification = notification(PAYMENT_PREFIX + "COMMIT");
        OutboundNotificationBatchReady batch = new OutboundNotificationBatchReady(List.of(notification));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            repository.insertAll(batch.notifications());
            eventPublisher.publishEvent(batch);

            verifyNoInteractions(pipeline);
            assertThat(storedRows(notification.communicationId())).isOne();
        });

        verify(pipeline).enqueue(batch);
        assertThat(storedRows(notification.communicationId())).isOne();
    }

    @Test
    void rolledBackRowsAreNeitherPersistedNorAdmitted() {
        NotificationPublication notification = notification(PAYMENT_PREFIX + "ROLLBACK");
        OutboundNotificationBatchReady batch = new OutboundNotificationBatchReady(List.of(notification));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            repository.insertAll(batch.notifications());
            eventPublisher.publishEvent(batch);
            status.setRollbackOnly();
        });

        verifyNoInteractions(pipeline);
        assertThat(storedRows(notification.communicationId())).isZero();
    }

    private int storedRows(String communicationId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_outbox WHERE communication_id = ?",
                Integer.class,
                communicationId
        );
    }

    private NotificationPublication notification(String id) {
        return NotificationPublication.create("20000001", id.getBytes(StandardCharsets.UTF_8), id);
    }
}
