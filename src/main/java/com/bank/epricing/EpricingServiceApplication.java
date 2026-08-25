package com.bank.epricing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class EpricingServiceApplication {

    private static final Logger log = LoggerFactory.getLogger(EpricingServiceApplication.class);

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(EpricingServiceApplication.class);
        app.run(args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        Environment env = event.getApplicationContext().getEnvironment();
        log.info("=============================================================");
        log.info("Bank ePricing Service started successfully");
        log.info("Application : {}", env.getProperty("spring.application.name"));
        log.info("Profile     : {}", String.join(", ", env.getActiveProfiles()));
        log.info("API Port    : {}", env.getProperty("server.port"));
        log.info("Actuator    : http://localhost:{}/actuator", env.getProperty("management.server.port", "8081"));
        log.info("Prometheus  : http://localhost:{}/actuator/prometheus", env.getProperty("management.server.port", "8081"));
        log.info("Yugabyte UI : http://localhost:15433");
        log.info("Health      : http://localhost:{}/actuator/health", env.getProperty("management.server.port", "8081"));
        log.info("=============================================================");
    }
}
