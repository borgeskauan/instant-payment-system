package br.kauan.notificationgateway.grpc;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PullRequestCoordinatorTest {

    @Test
    void permitsOnlyOneActivePullPerPspAndReleasesAdmissionOnClose() {
        PullRequestCoordinator coordinator = new PullRequestCoordinator();

        try (PullRequestCoordinator.Session ignored = coordinator.begin("20000001")) {
            assertThatThrownBy(() -> coordinator.begin("20000001"))
                    .isInstanceOf(PullRequestCoordinator.ConcurrentPullException.class);
        }

        try (PullRequestCoordinator.Session ignored = coordinator.begin("20000001")) {
            assertThat(ignored).isNotNull();
        }
    }

    @Test
    void wakesOnlyTheRecipientWhoseKafkaBatchCommitted() throws Exception {
        PullRequestCoordinator coordinator = new PullRequestCoordinator();
        var executor = Executors.newFixedThreadPool(2);
        try (PullRequestCoordinator.Session first = coordinator.begin("20000001");
             PullRequestCoordinator.Session second = coordinator.begin("20000002")) {
            var firstWait = executor.submit(() -> {
                first.await(Duration.ofSeconds(1));
                return null;
            });
            var secondWait = executor.submit(() -> {
                second.await(Duration.ofMillis(500));
                return null;
            });

            coordinator.signal(Set.of("20000001"));

            firstWait.get(200, TimeUnit.MILLISECONDS);
            assertThat(secondWait.isDone()).isFalse();
            secondWait.get(1, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
    }
}
