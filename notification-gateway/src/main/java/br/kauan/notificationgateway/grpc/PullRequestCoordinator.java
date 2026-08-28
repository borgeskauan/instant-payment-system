package br.kauan.notificationgateway.grpc;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Component
public final class PullRequestCoordinator {

    private final ConcurrentHashMap<String, Session> active = new ConcurrentHashMap<>();

    Session begin(String recipientIspb) {
        Session session = new Session(recipientIspb);
        if (active.putIfAbsent(recipientIspb, session) != null) {
            throw new ConcurrentPullException();
        }
        return session;
    }

    public void signal(Collection<String> recipients) {
        for (String recipient : recipients) {
            Session session = active.get(recipient);
            if (session != null) {
                session.signal();
            }
        }
    }

    final class Session implements AutoCloseable {

        private final String recipientIspb;
        private final CountDownLatch notificationAvailable = new CountDownLatch(1);

        private Session(String recipientIspb) {
            this.recipientIspb = recipientIspb;
        }

        void await(Duration timeout) throws InterruptedException {
            notificationAvailable.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        void signal() {
            notificationAvailable.countDown();
        }

        @Override
        public void close() {
            active.remove(recipientIspb, this);
        }
    }

    static final class ConcurrentPullException extends RuntimeException {
    }
}
