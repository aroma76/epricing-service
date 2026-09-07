package com.bank.epricing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  EpricingServiceApplication.java — Application Entry Point              ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║                                                                          ║
 * ║  WHY THIS CLASS EXISTS:                                                  ║
 * ║  Every Java application needs a main() method. For Spring Boot, this    ║
 * ║  class is the BOOTSTRAP — it starts the entire application:             ║
 * ║    1. Creates the Spring ApplicationContext                              ║
 * ║    2. Triggers auto-configuration (reads application.yml)                ║
 * ║    3. Starts the embedded Tomcat server                                  ║
 * ║    4. Initializes all Spring Beans (@Service, @Repository, etc.)        ║
 * ║    5. Opens the database connection pool (HikariCP)                     ║
 * ║    6. Registers all Actuator endpoints                                   ║
 * ║    7. Initializes Micrometer metrics registry                            ║
 * ║    8. Starts the OpenTelemetry SDK                                       ║
 * ║                                                                          ║
 * ║  HOW IT WORKS:                                                           ║
 * ║  @SpringBootApplication is a composite annotation combining:             ║
 * ║    @Configuration — This class can declare @Bean methods                 ║
 * ║    @EnableAutoConfiguration — Enables Spring Boot's magic auto-config   ║
 * ║    @ComponentScan — Scans sub-packages for @Component, @Service, etc.   ║
 * ║                                                                          ║
 * ║  WHAT WOULD HAPPEN WITHOUT IT:                                           ║
 * ║  There would be no entry point. The application cannot start.           ║
 * ║                                                                          ║
 * ║  RUNTIME SEQUENCE:                                                       ║
 * ║  JVM loads class → main() called → SpringApplication.run() →            ║
 * ║  ApplicationContext created → All beans initialized → Tomcat started →  ║
 * ║  ApplicationReadyEvent fired → Application serves traffic                ║
 * ║                                                                          ║
 * ║  INTERVIEW QUESTIONS:                                                    ║
 * ║  Q: What does @SpringBootApplication do?                                 ║
 * ║  Q: What is Spring ApplicationContext?                                   ║
 * ║  Q: What is the difference between ApplicationContext and BeanFactory?   ║
 * ║  Q: What is the order of Spring Boot auto-configuration?                 ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */
@SpringBootApplication
public class EpricingServiceApplication {

    private static final Logger log = LoggerFactory.getLogger(EpricingServiceApplication.class);

    /**
     * THE JAVA ENTRY POINT.
     *
     * SpringApplication.run() does the following:
     *   1. Creates a SpringApplication instance
     *   2. Determines the application type (SERVLET, REACTIVE, or NONE)
     *   3. Loads ApplicationContext initializers
     *   4. Loads ApplicationListeners
     *   5. Infers the main application class
     *   6. Calls run() which starts the context and Tomcat
     *
     * @param args Command-line arguments passed to the JVM.
     *             Example: --server.port=8090 overrides port from application.yml
     *             This is how you can have different ports on different environments.
     */
    public static void main(String[] args) {
        /*
         * WHY NOT: SpringApplication.run(EpricingServiceApplication.class, args);
         *
         * We use the builder pattern below to configure the application
         * before it starts. The simple one-liner is fine for basic apps,
         * but the builder gives more control for production systems.
         */
        SpringApplication app = new SpringApplication(EpricingServiceApplication.class);
        app.run(args);
    }

    /**
     * ApplicationReadyEvent listener — fires AFTER the application is fully started.
     *
     * WHY THIS EXISTS:
     * Sometimes you need to run initialization code AFTER all beans are wired
     * and the server is ready to serve traffic. Examples:
     *   - Warm up caches
     *   - Verify external service connectivity
     *   - Log startup banner with configuration summary
     *
     * This is safer than using @PostConstruct because:
     *   - @PostConstruct fires before the application is fully ready
     *   - ApplicationReadyEvent fires after ALL initialization is complete
     *   - If startup fails, this listener is never called
     *
     * @param event The event containing the ApplicationContext
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        Environment env = event.getApplicationContext().getEnvironment();

        // This INFO log proves our JSON logging is working from the first moment
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
