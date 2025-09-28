package br.kauan.paymentserviceprovider.commons;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
@Service
public class BackgroundTaskRunner {

    private final ScheduledExecutorService scheduler;
    private volatile boolean isLoopActive = false;

    public BackgroundTaskRunner() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @PreDestroy
    public void cleanup() {
        stopLoop();
        scheduler.shutdown();
    }

    public void startLoop(Runnable task) {
        if (isLoopActive) {
            return; // Loop is already running
        }

        isLoopActive = true;
        scheduler.submit(() -> {
            while (isLoopActive) {
                try {
                    task.run();
                    Thread.sleep(1000); // Sleep for a while to prevent tight loop
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Error in infinite loop task", e);
                }
            }
        });
    }

    public void stopLoop() {
        isLoopActive = false;
    }
}
