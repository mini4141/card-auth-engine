package com.cardauth.settlement;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SettlementServiceApplication {
    public static void main(String[] args) {
        new SpringApplicationBuilder(SettlementServiceApplication.class).properties("spring.config.name=settlement").run(args);
    }
}
