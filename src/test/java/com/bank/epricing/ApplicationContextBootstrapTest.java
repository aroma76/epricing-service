package com.bank.epricing;

import com.bank.epricing.config.DatabaseMigrationInitializer;
import com.bank.epricing.controller.HealthController;
import com.bank.epricing.controller.PricingController;
import com.bank.epricing.repository.PricingRepository;
import com.bank.epricing.service.PricingService;
import com.bank.epricing.util.PricingCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Enterprise Application Context Bootstrap Test.
 *
 * Verifies:
 * 1. Full Spring Boot 3.3.4 context bootstrap without wiring or property resolution failures.
 * 2. Automatic migration execution via DatabaseMigrationInitializer.
 * 3. HikariCP DataSource initialization and active database connectivity.
 * 4. JPA entity mapping validation and repository wiring.
 * 5. Web MVC dispatcher, context path, and health endpoint response.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationContextBootstrapTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PricingController pricingController;

    @Autowired
    private HealthController healthController;

    @Autowired
    private PricingService pricingService;

    @Autowired
    private PricingRepository pricingRepository;

    @Autowired
    private PricingCalculator pricingCalculator;

    @Autowired
    private DatabaseMigrationInitializer migrationInitializer;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("Application context boots successfully and all core banking beans are wired")
    void testContextLoadsAndBeansWired() {
        assertNotNull(applicationContext, "ApplicationContext must not be null");
        assertNotNull(pricingController, "PricingController must be wired");
        assertNotNull(healthController, "HealthController must be wired");
        assertNotNull(pricingService, "PricingService must be wired");
        assertNotNull(pricingRepository, "PricingRepository must be wired");
        assertNotNull(pricingCalculator, "PricingCalculator must be wired");
        assertNotNull(migrationInitializer, "DatabaseMigrationInitializer must be wired");
    }

    @Test
    @DisplayName("HikariCP DataSource establishes a valid database connection")
    void testDataSourceConnection() throws SQLException {
        assertNotNull(dataSource, "DataSource bean must be configured");
        try (Connection connection = dataSource.getConnection()) {
            assertNotNull(connection, "Active DB connection must be established");
            assertTrue(connection.isValid(5), "Connection must be valid");
        }
    }

    @Test
    @DisplayName("Health endpoint returns HTTP 200 with UP status via live HTTP container port")
    void testHealthEndpointLiveCall() {
        // TestRestTemplate automatically resolves relative paths against context-path (/api/v1)
        ResponseEntity<Map> response = restTemplate.getForEntity("/health", Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().get("status"));
    }
}
