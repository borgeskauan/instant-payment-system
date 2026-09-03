package br.kauan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan("br.kauan.spi.config")
public class SpiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpiApplication.class, args);
    }

}
