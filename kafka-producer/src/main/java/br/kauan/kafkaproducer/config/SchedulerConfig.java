package br.kauan.kafkaproducer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
}