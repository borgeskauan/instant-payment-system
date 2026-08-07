package br.kauan.spi.adapter.input;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.Executors;

@Configuration
public class SchedulerConfig {

    @Bean
    public Scheduler boundedElasticScheduler() {
        // Optimized scheduler for I/O operations
        return Schedulers.fromExecutor(Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors() * 2
        ));
    }

    @Bean(name = "taskScheduler")
    public TaskScheduler notificationOutboxTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("spi-outbox-");
        return scheduler;
    }
}
