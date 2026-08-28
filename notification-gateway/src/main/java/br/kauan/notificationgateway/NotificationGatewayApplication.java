package br.kauan.notificationgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan("br.kauan.notificationgateway.config")
@EnableScheduling
public class NotificationGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationGatewayApplication.class, args);
    }
}
