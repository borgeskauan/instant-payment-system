package br.kauan.spi.domain.services.notification;

import br.kauan.spi.domain.services.notification.dto.SpiNotification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class DeferredNotificationService {

    private static final long TIMEOUT_MS = 30000L;

    private final Map<String, DeferredResult<SpiNotification>> pendingRequests = new ConcurrentHashMap<>();

    public boolean isWaitingForNotification(String ispb) {
        return pendingRequests.containsKey(ispb);
    }

    public void sendNotification(String ispb, SpiNotification notification) {
        DeferredResult<SpiNotification> deferredResult = pendingRequests.remove(ispb);
        if (deferredResult != null && !deferredResult.isSetOrExpired()) {
            deferredResult.setResult(notification);
        }
    }

    public DeferredResult<SpiNotification> getNotification(String ispb) {
        DeferredResult<SpiNotification> deferredResult = new DeferredResult<>(TIMEOUT_MS);

        // Use putIfAbsent to handle concurrent requests
        var existing = pendingRequests.putIfAbsent(ispb, deferredResult);
        if (existing != null) {
            throw new IllegalStateException("Already waiting for notification for ISPB: " + ispb);
        }

        setupCompletionHandlers(ispb, deferredResult);
        return deferredResult;
    }

    private void setupCompletionHandlers(String ispb, DeferredResult<SpiNotification> deferredResult) {
        deferredResult.onCompletion(() -> {
            // Remove only if it's still the same instance
            pendingRequests.remove(ispb, deferredResult);
        });

        deferredResult.onTimeout(() -> {
            pendingRequests.remove(ispb, deferredResult);
            deferredResult.setErrorResult(new TimeoutException("Notification timeout"));
        });

        deferredResult.onError((throwable) -> {
            pendingRequests.remove(ispb, deferredResult);
            log.error("Error in deferred result for ISPB: {}", ispb, throwable);
        });
    }
}
