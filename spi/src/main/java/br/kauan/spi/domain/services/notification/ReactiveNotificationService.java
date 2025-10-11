package br.kauan.spi.domain.services.notification;

import br.kauan.spi.domain.services.notification.dto.SpiNotification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ReactiveNotificationService {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final Map<String, Sinks.Many<SpiNotification>> notificationSinks = new ConcurrentHashMap<>();

    public Flux<SpiNotification> getNotificationStream(String ispb) {
        Sinks.Many<SpiNotification> sink = notificationSinks.computeIfAbsent(ispb,
                key -> Sinks.many().multicast().onBackpressureBuffer(100, false));

        return sink.asFlux()
                .timeout(TIMEOUT, Flux.defer(() -> {
                    log.debug("Notification stream timeout for ISPB: {}", ispb);
                    cleanupSink(ispb, sink);
                    return Flux.just(SpiNotification.empty());
                }))
                .doOnCancel(() -> {
                    log.debug("Notification stream cancelled for ISPB: {}", ispb);
                    cleanupSink(ispb, sink);
                })
                .doOnTerminate(() -> cleanupSink(ispb, sink))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public void sendNotification(String ispb, SpiNotification notification) {
        Sinks.Many<SpiNotification> sink = notificationSinks.get(ispb);
        if (sink != null) {
            Sinks.EmitResult result = sink.tryEmitNext(notification);
            if (result.isFailure()) {
                log.warn("Failed to send notification to ISPB: {}, reason: {}", ispb, result);
                // Retry logic or alternative handling could be added here
            }
        }
    }

    private void cleanupSink(String ispb, Sinks.Many<SpiNotification> sink) {
        notificationSinks.remove(ispb, sink);
        if (sink != null) {
            sink.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST);
        }
    }
}