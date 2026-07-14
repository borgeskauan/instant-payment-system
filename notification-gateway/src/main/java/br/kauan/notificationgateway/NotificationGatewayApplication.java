package br.kauan.notificationgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class NotificationGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationGatewayApplication.class, args);
    }
}
