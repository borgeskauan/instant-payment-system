package br.kauan.spi.adapter.output.outbox;

import br.kauan.spi.adapter.output.kafka.NotificationPublication;
import br.kauan.spi.adapter.output.kafka.NotificationPublisher;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationOutboxWorkerTest {

    @Test
    void committedBatchPublishesWithoutReadingTheOutboxAgain() {
        NotificationOutboxRepository repository = mock(NotificationOutboxRepository.class);
        NotificationPublisher publisher = mock(NotificationPublisher.class);
        NotificationPublication notification = notification("E2E-DIRECT");
        when(publisher.publish(notification))
                .thenReturn(CompletableFuture.completedFuture(sendResult(0)));
        NotificationOutboxWorker worker = worker(repository, publisher);

        worker.publishCommitted(new NotificationOutboxBatchReady(List.of(notification)));

        verify(repository, never()).findPending(any(Integer.class));
        verify(repository).markPublished(List.of(notification.communicationId()));
        verify(repository, never()).scheduleRetry(any(), any());
    }

    @Test
    void recoveryWaitsUntilCommittedBatchPublicationFinishes() throws Exception {
        NotificationOutboxRepository repository = mock(NotificationOutboxRepository.class);
        NotificationPublisher publisher = mock(NotificationPublisher.class);
        NotificationPublication committed = notification("E2E-DIRECT-SERIALIZED");
        CompletableFuture<SendResult<String, byte[]>> committedSend = new CompletableFuture<>();
        CountDownLatch recoveryRead = new CountDownLatch(1);
        when(publisher.publish(committed)).thenReturn(committedSend);
        when(repository.findPending(1_000)).thenAnswer(invocation -> {
            recoveryRead.countDown();
            return List.of();
        });
        NotificationOutboxWorker worker = worker(repository, publisher);

        CompletableFuture<Void> direct = CompletableFuture.runAsync(
                () -> worker.publishCommitted(new NotificationOutboxBatchReady(List.of(committed)))
        );
        waitUntilBothSendsStarted(publisher, 1);
        CompletableFuture<Void> recovery = CompletableFuture.runAsync(worker::publishPending);

        assertThat(recoveryRead.await(100, TimeUnit.MILLISECONDS)).isFalse();
        committedSend.complete(sendResult(0));
        direct.get(2, TimeUnit.SECONDS);
        recovery.get(2, TimeUnit.SECONDS);
        assertThat(recoveryRead.getCount()).isZero();
    }

    @Test
    void startsEverySendBeforeWaitingAndMarksRowsOnlyAfterBrokerConfirmation() throws Exception {
        NotificationOutboxRepository repository = mock(NotificationOutboxRepository.class);
        NotificationPublisher publisher = mock(NotificationPublisher.class);
        NotificationPublication first = notification("E2E-1");
        NotificationPublication second = notification("E2E-2");
        CompletableFuture<SendResult<String, byte[]>> firstFuture = new CompletableFuture<>();
        CompletableFuture<SendResult<String, byte[]>> secondFuture = new CompletableFuture<>();
        when(repository.findPending(1_000)).thenReturn(List.of(first, second));
        when(publisher.publish(first)).thenReturn(firstFuture);
        when(publisher.publish(second)).thenReturn(secondFuture);
        NotificationOutboxWorker worker = worker(repository, publisher);

        CompletableFuture<Void> execution = CompletableFuture.runAsync(worker::publishPending);
        waitUntilBothSendsStarted(publisher);

        verify(repository, never()).markPublished(any());
        firstFuture.complete(sendResult(0));
        verify(repository, never()).markPublished(any());
        secondFuture.complete(sendResult(1));
        execution.get(2, TimeUnit.SECONDS);

        verify(repository).markPublished(List.of(first.communicationId(), second.communicationId()));
        verify(repository, never()).scheduleRetry(any(), any());
    }

    @Test
    void updatesSuccessfulAndFailedRowsInSeparateBulkSets() {
        NotificationOutboxRepository repository = mock(NotificationOutboxRepository.class);
        NotificationPublisher publisher = mock(NotificationPublisher.class);
        NotificationPublication succeeded = notification("E2E-SUCCESS");
        NotificationPublication failed = notification("E2E-FAILURE");
        when(repository.findPending(1_000)).thenReturn(List.of(succeeded, failed));
        when(publisher.publish(succeeded))
                .thenReturn(CompletableFuture.completedFuture(sendResult(0)));
        when(publisher.publish(failed))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        NotificationOutboxWorker worker = worker(repository, publisher);

        worker.publishPending();

        verify(repository).markPublished(List.of(succeeded.communicationId()));
        ArgumentCaptor<List<NotificationPublicationFailure>> failures = ArgumentCaptor.forClass(List.class);
        verify(repository).scheduleRetry(failures.capture(), org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(1)));
        assertThat(failures.getValue()).containsExactly(new NotificationPublicationFailure(
                failed.communicationId(),
                "java.lang.IllegalStateException: broker unavailable"
        ));
    }

    @Test
    void synchronousSendFailureDoesNotPreventOtherRowsFromStarting() {
        NotificationOutboxRepository repository = mock(NotificationOutboxRepository.class);
        NotificationPublisher publisher = mock(NotificationPublisher.class);
        NotificationPublication first = notification("E2E-SYNC-FAILURE");
        NotificationPublication second = notification("E2E-SUCCESS");
        when(repository.findPending(1_000)).thenReturn(List.of(first, second));
        when(publisher.publish(first)).thenThrow(new IllegalStateException("producer closed"));
        when(publisher.publish(second)).thenReturn(CompletableFuture.completedFuture(sendResult(0)));

        worker(repository, publisher).publishPending();

        verify(publisher).publish(second);
        verify(repository).markPublished(List.of(second.communicationId()));
        verify(repository).scheduleRetry(any(), org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(1)));
    }

    @Test
    void successfulSendIsPublishedAgainWhenDatabaseUpdateFails() {
        NotificationOutboxRepository repository = mock(NotificationOutboxRepository.class);
        NotificationPublisher publisher = mock(NotificationPublisher.class);
        NotificationPublication notification = notification("E2E-UPDATE-FAILURE");
        when(repository.findPending(1_000)).thenReturn(List.of(notification));
        when(publisher.publish(notification))
                .thenReturn(CompletableFuture.completedFuture(sendResult(0)));
        when(repository.markPublished(List.of(notification.communicationId())))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(1);
        NotificationOutboxWorker worker = worker(repository, publisher);

        worker.publishPending();
        worker.publishPending();

        verify(publisher, times(2)).publish(notification);
        verify(repository, times(2)).markPublished(List.of(notification.communicationId()));
    }

    private NotificationOutboxWorker worker(
            NotificationOutboxRepository repository,
            NotificationPublisher publisher
    ) {
        return new NotificationOutboxWorker(repository, publisher, 1_000, Duration.ofSeconds(1));
    }

    private NotificationPublication notification(String paymentId) {
        return NotificationPublication.create(
                "20000001",
                ("{\"paymentId\":\"" + paymentId + "\"}").getBytes(StandardCharsets.UTF_8),
                "ACCEPTANCE_REQUEST",
                paymentId,
                null
        );
    }

    private SendResult<String, byte[]> sendResult(int partition) {
        return new SendResult<>(null, new RecordMetadata(
                new TopicPartition("psp-notifications", partition),
                10L,
                0,
                0,
                0,
                0));
    }

    private void waitUntilBothSendsStarted(NotificationPublisher publisher) throws InterruptedException {
        waitUntilBothSendsStarted(publisher, 2);
    }

    private void waitUntilBothSendsStarted(NotificationPublisher publisher, int expected) throws InterruptedException {
        AssertionError lastFailure = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            try {
                verify(publisher, times(expected)).publish(any());
                return;
            } catch (AssertionError failure) {
                lastFailure = failure;
                Thread.sleep(10);
            }
        }
        throw lastFailure;
    }
}
