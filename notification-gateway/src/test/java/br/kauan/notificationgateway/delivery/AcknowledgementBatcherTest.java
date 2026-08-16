package br.kauan.notificationgateway.delivery;

import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AcknowledgementBatcherTest {

    @Test
    void startsBeforeTheGrpcServerLifecyclePhase() {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        AcknowledgementBatcher batcher = batcher(repository, 1, Duration.ofMillis(1), 1);

        assertThat(batcher.getPhase()).isLessThan(Integer.MAX_VALUE);
    }

    @Test
    void flushesImmediatelyWhenBatchSizeIsReached() throws Exception {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        FlushProbe probe = new FlushProbe(1);
        when(repository.acknowledgeAll(anyList())).thenAnswer(probe);
        AcknowledgementBatcher batcher = batcher(repository, 2, Duration.ofSeconds(1), 4);
        batcher.start();
        try {
            assertThat(batcher.enqueue(ack("v1:first"))).isTrue();
            assertThat(batcher.enqueue(ack("v1:second"))).isTrue();

            assertThat(probe.awaitFlush(Duration.ofSeconds(1))).isTrue();
            assertThat(probe.batches()).containsExactly(List.of(
                    ack("v1:first"), ack("v1:second")
            ));
        } finally {
            batcher.stop();
        }
    }

    @Test
    void flushesPartialBatchWhenMaximumWaitExpires() throws Exception {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        FlushProbe probe = new FlushProbe(1);
        when(repository.acknowledgeAll(anyList())).thenAnswer(probe);
        AcknowledgementBatcher batcher = batcher(repository, 500, Duration.ofMillis(20), 10_000);
        batcher.start();
        try {
            assertThat(batcher.enqueue(ack("v1:first"))).isTrue();
            assertThat(probe.awaitFlush(Duration.ofSeconds(1))).isTrue();
            assertThat(probe.batches()).containsExactly(List.of(ack("v1:first")));
        } finally {
            batcher.stop();
        }
    }

    @Test
    void collapsesDuplicateAuthenticatedIdentitiesInsideOneBatch() throws Exception {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        FlushProbe probe = new FlushProbe(1);
        when(repository.acknowledgeAll(anyList())).thenAnswer(probe);
        AcknowledgementBatcher batcher = batcher(repository, 3, Duration.ofSeconds(1), 4);
        batcher.start();
        try {
            batcher.enqueue(ack("v1:first"));
            batcher.enqueue(ack("v1:first"));
            batcher.enqueue(ack("v1:second"));

            assertThat(probe.awaitFlush(Duration.ofSeconds(1))).isTrue();
            assertThat(probe.batches()).containsExactly(List.of(
                    ack("v1:first"), ack("v1:second")
            ));
        } finally {
            batcher.stop();
        }
    }

    @Test
    void invokesTheRepositoryFromOnlyOneWriterAtATime() throws Exception {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        BlockingFlushProbe probe = new BlockingFlushProbe(2);
        when(repository.acknowledgeAll(anyList())).thenAnswer(probe);
        AcknowledgementBatcher batcher = batcher(repository, 1, Duration.ofSeconds(1), 4);
        batcher.start();
        try {
            assertThat(batcher.enqueue(ack("v1:first"))).isTrue();
            assertThat(probe.awaitFirstCall(Duration.ofSeconds(1))).isTrue();
            assertThat(batcher.enqueue(ack("v1:second"))).isTrue();

            probe.releaseFirstCall();

            assertThat(probe.awaitFlushes(Duration.ofSeconds(1))).isTrue();
            assertThat(probe.maximumConcurrentFlushes()).isEqualTo(1);
            assertThat(probe.batches()).containsExactly(
                    List.of(ack("v1:first")),
                    List.of(ack("v1:second"))
            );
        } finally {
            probe.releaseFirstCall();
            batcher.stop();
        }
    }

    @Test
    void blocksWhenQueueIsFullWithoutDroppingOrSynchronousFallback() throws Exception {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        BlockingFlushProbe probe = new BlockingFlushProbe(3);
        when(repository.acknowledgeAll(anyList())).thenAnswer(probe);
        AcknowledgementBatcher batcher = batcher(repository, 1, Duration.ofSeconds(1), 1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        batcher.start();
        try {
            assertThat(batcher.enqueue(ack("v1:first"))).isTrue();
            assertThat(probe.awaitFirstCall(Duration.ofSeconds(1))).isTrue();
            assertThat(batcher.enqueue(ack("v1:second"))).isTrue();
            CountDownLatch producerStarted = new CountDownLatch(1);
            Future<Boolean> blocked = executor.submit(() -> {
                producerStarted.countDown();
                return batcher.enqueue(ack("v1:third"));
            });
            assertThat(producerStarted.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> blocked.get(50, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            probe.releaseFirstCall();
            assertThat(blocked.get(1, TimeUnit.SECONDS)).isTrue();
            assertThat(probe.awaitFlushes(Duration.ofSeconds(1))).isTrue();
            assertThat(probe.batches()).containsExactly(
                    List.of(ack("v1:first")),
                    List.of(ack("v1:second")),
                    List.of(ack("v1:third"))
            );
            assertThat(probe.maximumConcurrentFlushes()).isEqualTo(1);
        } finally {
            probe.releaseFirstCall();
            batcher.stop();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rejectedShutdownAdmissionNeverReachesPersistence() throws Exception {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        InterruptCompletingFlushProbe probe = new InterruptCompletingFlushProbe(2);
        when(repository.acknowledgeAll(anyList())).thenAnswer(probe);
        AcknowledgementBatcher batcher = new AcknowledgementBatcher(
                repository,
                1,
                Duration.ofSeconds(1),
                1,
                Duration.ofMillis(1),
                Duration.ofSeconds(1)
        );
        ExecutorService executor = Executors.newSingleThreadExecutor();
        batcher.start();
        try {
            assertThat(batcher.enqueue(ack("v1:first"))).isTrue();
            assertThat(probe.awaitFirstCall(Duration.ofSeconds(1))).isTrue();
            assertThat(batcher.enqueue(ack("v1:second"))).isTrue();
            CountDownLatch producerStarted = new CountDownLatch(1);
            Future<Boolean> raced = executor.submit(() -> {
                producerStarted.countDown();
                return batcher.enqueue(ack("v1:raced"));
            });
            assertThat(producerStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> raced.get(50, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            batcher.stop();

            assertThat(raced.get(1, TimeUnit.SECONDS)).isFalse();
            assertThat(probe.awaitFlushes(Duration.ofSeconds(1))).isTrue();
            assertThat(probe.batches()).containsExactly(
                    List.of(ack("v1:first")),
                    List.of(ack("v1:second"))
            );
        } finally {
            batcher.stop();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void drainsAlreadyQueuedShutdownWorkInConfiguredSizeBatches() throws Exception {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        InterruptCompletingFlushProbe probe = new InterruptCompletingFlushProbe(2);
        when(repository.acknowledgeAll(anyList())).thenAnswer(probe);
        AcknowledgementBatcher batcher = new AcknowledgementBatcher(
                repository,
                4,
                Duration.ofSeconds(1),
                4,
                Duration.ofMillis(1),
                Duration.ofSeconds(1)
        );
        batcher.start();
        try {
            assertThat(batcher.enqueue(ack("v1:first-1"))).isTrue();
            assertThat(batcher.enqueue(ack("v1:first-2"))).isTrue();
            assertThat(batcher.enqueue(ack("v1:first-3"))).isTrue();
            assertThat(batcher.enqueue(ack("v1:first-4"))).isTrue();
            assertThat(probe.awaitFirstCall(Duration.ofSeconds(1))).isTrue();
            assertThat(batcher.enqueue(ack("v1:second-1"))).isTrue();
            assertThat(batcher.enqueue(ack("v1:second-2"))).isTrue();
            assertThat(batcher.enqueue(ack("v1:second-3"))).isTrue();
            assertThat(batcher.enqueue(ack("v1:second-4"))).isTrue();

            batcher.stop();

            assertThat(probe.awaitFlushes(Duration.ofSeconds(1))).isTrue();
            assertThat(probe.batches()).containsExactly(
                    List.of(
                            ack("v1:first-1"), ack("v1:first-2"),
                            ack("v1:first-3"), ack("v1:first-4")
                    ),
                    List.of(
                            ack("v1:second-1"), ack("v1:second-2"),
                            ack("v1:second-3"), ack("v1:second-4")
                    )
            );
        } finally {
            batcher.stop();
        }
    }

    @Test
    void retriesTheExactRetainedBatchBeforeReadingLaterAcknowledgements() throws Exception {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        RetryingFlushProbe probe = new RetryingFlushProbe(
                new DataAccessResourceFailureException("database unavailable")
        );
        when(repository.acknowledgeAll(anyList())).thenAnswer(probe);
        AcknowledgementBatcher batcher = new AcknowledgementBatcher(
                repository,
                3,
                Duration.ofMillis(20),
                4,
                Duration.ofMillis(1),
                Duration.ofSeconds(1)
        );
        batcher.start();
        try {
            assertThat(batcher.enqueue(ack("v1:first"))).isTrue();
            assertThat(batcher.enqueue(ack("v1:first"))).isTrue();
            assertThat(batcher.enqueue(ack("v1:second"))).isTrue();
            assertThat(probe.awaitFirstAttempt(Duration.ofSeconds(1))).isTrue();
            assertThat(batcher.enqueue(ack("v1:later"))).isTrue();

            probe.failFirstAttempt();

            assertThat(probe.awaitAttempts(Duration.ofSeconds(1))).isTrue();
            assertThat(probe.batches()).containsExactly(
                    List.of(ack("v1:first"), ack("v1:second")),
                    List.of(ack("v1:first"), ack("v1:second")),
                    List.of(ack("v1:later"))
            );
        } finally {
            probe.failFirstAttempt();
            batcher.stop();
        }
    }

    @Test
    void retriesTheExactRetainedBatchAfterTransactionBeginFailure() throws Exception {
        assertTransactionFailureRetainsBatchBeforeLaterAcknowledgements(
                new CannotCreateTransactionException("cannot begin transaction")
        );
    }

    @Test
    void retriesTheExactRetainedBatchAfterTransactionCommitFailure() throws Exception {
        assertTransactionFailureRetainsBatchBeforeLaterAcknowledgements(
                new TransactionSystemException("cannot commit transaction")
        );
    }

    @Test
    void flushesAPartialBatchBeforeStopReturns() throws Exception {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        FlushProbe probe = new FlushProbe(1);
        when(repository.acknowledgeAll(anyList())).thenAnswer(probe);
        AcknowledgementBatcher batcher = batcher(repository, 500, Duration.ofSeconds(30), 4);
        batcher.start();

        assertThat(batcher.enqueue(ack("v1:first"))).isTrue();
        batcher.stop();

        assertThat(probe.awaitFlush(Duration.ofSeconds(1))).isTrue();
        assertThat(probe.batches()).containsExactly(List.of(ack("v1:first")));
        assertThat(batcher.isRunning()).isFalse();
    }

    @Test
    void returnsFromStopWithinTheShutdownTimeoutWhenPersistenceKeepsFailing() throws Exception {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        CountDownLatch attempted = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        when(repository.acknowledgeAll(anyList())).thenAnswer(invocation -> {
            attempts.incrementAndGet();
            attempted.countDown();
            throw new DataAccessResourceFailureException("database unavailable");
        });
        AcknowledgementBatcher batcher = new AcknowledgementBatcher(
                repository,
                1,
                Duration.ofSeconds(1),
                1,
                Duration.ofMillis(1),
                Duration.ofMillis(50)
        );
        batcher.start();
        assertThat(batcher.enqueue(ack("v1:first"))).isTrue();
        assertThat(attempted.await(1, TimeUnit.SECONDS)).isTrue();

        long startedAt = System.nanoTime();
        batcher.stop();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
        assertThat(attempts.get()).isPositive();
        assertThat(batcher.isRunning()).isFalse();
    }

    @Test
    void rejectsEnqueueBeforeStart() throws Exception {
        AcknowledgementBatcher batcher = batcher(
                mock(NotificationDeliveryRepository.class),
                1,
                Duration.ofMillis(20),
                1
        );

        assertThat(batcher.enqueue(ack("v1:first"))).isFalse();
        assertThat(batcher.isRunning()).isFalse();
    }

    @Test
    void rejectsEnqueueAfterStop() throws Exception {
        AcknowledgementBatcher batcher = batcher(
                mock(NotificationDeliveryRepository.class),
                1,
                Duration.ofMillis(20),
                1
        );
        batcher.start();
        batcher.stop();

        assertThat(batcher.enqueue(ack("v1:first"))).isFalse();
        assertThat(batcher.isRunning()).isFalse();
    }

    @Test
    void propagatesInterruptionFromAProducerBlockedByAFullQueue() throws Exception {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        UninterruptibleFlushProbe probe = new UninterruptibleFlushProbe();
        when(repository.acknowledgeAll(anyList())).thenAnswer(probe);
        AcknowledgementBatcher batcher = new AcknowledgementBatcher(
                repository,
                1,
                Duration.ofSeconds(1),
                1,
                Duration.ofMillis(1),
                Duration.ofMillis(50)
        );
        ExecutorService executor = Executors.newSingleThreadExecutor();
        batcher.start();
        try {
            assertThat(batcher.enqueue(ack("v1:first"))).isTrue();
            assertThat(probe.awaitFirstCall(Duration.ofSeconds(1))).isTrue();
            assertThat(batcher.enqueue(ack("v1:second"))).isTrue();
            AtomicReference<Thread> producerThread = new AtomicReference<>();
            CountDownLatch producerStarted = new CountDownLatch(1);
            Future<Throwable> blocked = executor.submit(() -> {
                producerThread.set(Thread.currentThread());
                producerStarted.countDown();
                try {
                    batcher.enqueue(ack("v1:third"));
                    return null;
                } catch (InterruptedException interrupted) {
                    return interrupted;
                }
            });
            assertThat(producerStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> blocked.get(50, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            producerThread.get().interrupt();

            assertThat(blocked.get(1, TimeUnit.SECONDS)).isInstanceOf(InterruptedException.class);
        } finally {
            probe.release();
            batcher.stop();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void wakesAProducerBlockedByAFullQueueWhenStopBegins() throws Exception {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        UninterruptibleFlushProbe probe = new UninterruptibleFlushProbe();
        when(repository.acknowledgeAll(anyList())).thenAnswer(probe);
        AcknowledgementBatcher batcher = new AcknowledgementBatcher(
                repository,
                1,
                Duration.ofSeconds(1),
                1,
                Duration.ofMillis(1),
                Duration.ofMillis(50)
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        batcher.start();
        try {
            assertThat(batcher.enqueue(ack("v1:first"))).isTrue();
            assertThat(probe.awaitFirstCall(Duration.ofSeconds(1))).isTrue();
            assertThat(batcher.enqueue(ack("v1:second"))).isTrue();
            CountDownLatch producerStarted = new CountDownLatch(1);
            Future<Boolean> blocked = executor.submit(() -> {
                producerStarted.countDown();
                return batcher.enqueue(ack("v1:third"));
            });
            assertThat(producerStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> blocked.get(50, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            Future<?> stopped = executor.submit((Runnable) batcher::stop);

            stopped.get(1, TimeUnit.SECONDS);
            assertThat(blocked.get(1, TimeUnit.SECONDS)).isFalse();
        } finally {
            probe.release();
            assertThat(probe.awaitCallFinished(Duration.ofSeconds(1))).isTrue();
            batcher.stop();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rejectsNonPositiveTuningValues() {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);

        assertThatIllegalArgumentException().isThrownBy(() -> new AcknowledgementBatcher(
                repository, 0, Duration.ofMillis(1), 1, Duration.ofMillis(1), Duration.ofMillis(1)
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> new AcknowledgementBatcher(
                repository, 1, Duration.ZERO, 1, Duration.ofMillis(1), Duration.ofMillis(1)
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> new AcknowledgementBatcher(
                repository, 1, Duration.ofMillis(1), 0, Duration.ofMillis(1), Duration.ofMillis(1)
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> new AcknowledgementBatcher(
                repository, 1, Duration.ofMillis(1), 1, Duration.ZERO, Duration.ofMillis(1)
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> new AcknowledgementBatcher(
                repository, 1, Duration.ofMillis(1), 1, Duration.ofMillis(1), Duration.ZERO
        ));
    }

    @Test
    void rejectsDurationsTooLargeForNanosecondDeadlines() {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);

        assertThatIllegalArgumentException().isThrownBy(() -> new AcknowledgementBatcher(
                repository,
                1,
                Duration.ofSeconds(Long.MAX_VALUE),
                1,
                Duration.ofMillis(1),
                Duration.ofMillis(1)
        ));
    }

    private AcknowledgementBatcher batcher(
            NotificationDeliveryRepository repository,
            int batchSize,
            Duration flushInterval,
            int queueCapacity
    ) {
        return new AcknowledgementBatcher(
                repository,
                batchSize,
                flushInterval,
                queueCapacity,
                Duration.ofMillis(1),
                Duration.ofSeconds(1)
        );
    }

    private void assertTransactionFailureRetainsBatchBeforeLaterAcknowledgements(
            RuntimeException transactionFailure
    ) throws Exception {
        NotificationDeliveryRepository repository = mock(NotificationDeliveryRepository.class);
        RetryingFlushProbe probe = new RetryingFlushProbe(transactionFailure);
        when(repository.acknowledgeAll(anyList())).thenAnswer(probe);
        AcknowledgementBatcher batcher = new AcknowledgementBatcher(
                repository,
                3,
                Duration.ofMillis(20),
                4,
                Duration.ofMillis(1),
                Duration.ofSeconds(1)
        );
        batcher.start();
        try {
            assertThat(batcher.enqueue(ack("v1:first"))).isTrue();
            assertThat(batcher.enqueue(ack("v1:first"))).isTrue();
            assertThat(batcher.enqueue(ack("v1:second"))).isTrue();
            assertThat(probe.awaitFirstAttempt(Duration.ofSeconds(1))).isTrue();
            assertThat(batcher.enqueue(ack("v1:later"))).isTrue();

            probe.failFirstAttempt();

            assertThat(probe.awaitAttempts(Duration.ofSeconds(1))).isTrue();
            assertThat(probe.batches()).containsExactly(
                    List.of(ack("v1:first"), ack("v1:second")),
                    List.of(ack("v1:first"), ack("v1:second")),
                    List.of(ack("v1:later"))
            );
        } finally {
            probe.failFirstAttempt();
            batcher.stop();
        }
    }

    private Acknowledgement ack(String communicationId) {
        return new Acknowledgement(communicationId, "20000001");
    }

    private static class FlushProbe implements Answer<Integer> {

        private final List<List<Acknowledgement>> batches = new CopyOnWriteArrayList<>();
        private final CountDownLatch flushes;

        private FlushProbe(int expectedFlushes) {
            this.flushes = new CountDownLatch(expectedFlushes);
        }

        @Override
        public Integer answer(InvocationOnMock invocation) throws Throwable {
            List<Acknowledgement> batch = List.copyOf(invocation.getArgument(0));
            batches.add(batch);
            flushes.countDown();
            return batch.size();
        }

        boolean awaitFlush(Duration timeout) throws InterruptedException {
            return awaitFlushes(timeout);
        }

        boolean awaitFlushes(Duration timeout) throws InterruptedException {
            return flushes.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        List<List<Acknowledgement>> batches() {
            return List.copyOf(batches);
        }
    }

    private static final class BlockingFlushProbe extends FlushProbe {

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger activeFlushes = new AtomicInteger();
        private final AtomicInteger maximumConcurrentFlushes = new AtomicInteger();
        private final CountDownLatch firstCallEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirstCall = new CountDownLatch(1);

        private BlockingFlushProbe(int expectedFlushes) {
            super(expectedFlushes);
        }

        @Override
        public Integer answer(InvocationOnMock invocation) throws Throwable {
            int active = activeFlushes.incrementAndGet();
            maximumConcurrentFlushes.accumulateAndGet(active, Math::max);
            try {
                if (calls.incrementAndGet() == 1) {
                    firstCallEntered.countDown();
                    releaseFirstCall.await();
                }
                return super.answer(invocation);
            } finally {
                activeFlushes.decrementAndGet();
            }
        }

        boolean awaitFirstCall(Duration timeout) throws InterruptedException {
            return firstCallEntered.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        void releaseFirstCall() {
            releaseFirstCall.countDown();
        }

        int maximumConcurrentFlushes() {
            return maximumConcurrentFlushes.get();
        }
    }

    private static final class RetryingFlushProbe implements Answer<Integer> {

        private final List<List<Acknowledgement>> batches = new CopyOnWriteArrayList<>();
        private final CountDownLatch firstAttemptEntered = new CountDownLatch(1);
        private final CountDownLatch failFirstAttempt = new CountDownLatch(1);
        private final CountDownLatch attempts = new CountDownLatch(3);
        private final AtomicInteger calls = new AtomicInteger();
        private final RuntimeException firstFailure;

        private RetryingFlushProbe(RuntimeException firstFailure) {
            this.firstFailure = firstFailure;
        }

        @Override
        public Integer answer(InvocationOnMock invocation) throws Throwable {
            List<Acknowledgement> batch = List.copyOf(invocation.getArgument(0));
            batches.add(batch);
            int call = calls.incrementAndGet();
            attempts.countDown();
            if (call == 1) {
                firstAttemptEntered.countDown();
                failFirstAttempt.await();
                throw firstFailure;
            }
            return batch.size();
        }

        boolean awaitFirstAttempt(Duration timeout) throws InterruptedException {
            return firstAttemptEntered.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        void failFirstAttempt() {
            failFirstAttempt.countDown();
        }

        boolean awaitAttempts(Duration timeout) throws InterruptedException {
            return attempts.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        List<List<Acknowledgement>> batches() {
            return List.copyOf(batches);
        }
    }

    private static final class InterruptCompletingFlushProbe extends FlushProbe {

        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch firstCallEntered = new CountDownLatch(1);
        private final CountDownLatch waitForInterrupt = new CountDownLatch(1);

        private InterruptCompletingFlushProbe(int expectedFlushes) {
            super(expectedFlushes);
        }

        @Override
        public Integer answer(InvocationOnMock invocation) throws Throwable {
            if (calls.incrementAndGet() == 1) {
                firstCallEntered.countDown();
                try {
                    waitForInterrupt.await();
                } catch (InterruptedException shutdownWakeUp) {
                    // Models a JDBC call that completes when shutdown interrupts it.
                }
            }
            return super.answer(invocation);
        }

        boolean awaitFirstCall(Duration timeout) throws InterruptedException {
            return firstCallEntered.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    private static final class UninterruptibleFlushProbe implements Answer<Integer> {

        private final CountDownLatch firstCallEntered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch callFinished = new CountDownLatch(1);

        @Override
        public Integer answer(InvocationOnMock invocation) {
            firstCallEntered.countDown();
            boolean finished = false;
            while (!finished) {
                try {
                    release.await();
                    finished = true;
                } catch (InterruptedException ignored) {
                    // Simulates a JDBC call that ignores interruption.
                }
            }
            callFinished.countDown();
            return ((List<?>) invocation.getArgument(0)).size();
        }

        boolean awaitFirstCall(Duration timeout) throws InterruptedException {
            return firstCallEntered.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        void release() {
            release.countDown();
        }

        boolean awaitCallFinished(Duration timeout) throws InterruptedException {
            return callFinished.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }
    }
}
