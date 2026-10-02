package dev.applymcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ApplyMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApplyMcpServerApplication.class, args);
    }
}
