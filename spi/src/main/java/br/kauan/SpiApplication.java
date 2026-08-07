package br.kauan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class SpiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpiApplication.class, args);
    }

}
