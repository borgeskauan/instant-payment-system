package br.kauan.notificationgateway.delivery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class NotificationDeliveryDispatchConfig {

    @Bean(name = "notificationDispatchExecutor")
    public ThreadPoolTaskExecutor notificationDispatchExecutor(
            @Value("${notification-gateway.delivery.dispatch-concurrency:8}") int dispatchConcurrency
    ) {
        int poolSize = Math.max(1, dispatchConcurrency);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(10_000);
        executor.setThreadNamePrefix("notification-dispatch-");
        return executor;
    }
}
