package br.kauan.paymentserviceprovider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableFeignClients
@ConfigurationPropertiesScan
@SpringBootApplication
public class PaymentServiceProviderApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceProviderApplication.class, args);
    }

}
