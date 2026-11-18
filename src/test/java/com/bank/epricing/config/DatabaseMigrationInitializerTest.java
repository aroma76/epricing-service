package com.bank.epricing.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseMigrationInitializerTest {

    @Test
    @DisplayName("Should execute database migrations sequentially on H2 database without failure")
    void shouldExecuteDatabaseMigrationsSequentially() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:migration_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        dataSource.setUsername("sa");
        dataSource.setPassword("");

        DatabaseMigrationInitializer initializer = new DatabaseMigrationInitializer(dataSource);
        assertDoesNotThrow(initializer::runMigrations);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // Verify schema_history has recorded applied migrations
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM schema_history")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) >= 5, "Expected at least 5 migrations applied");
            }

            // Verify core table exists and initial seed data from V4 is applied
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pricing_requests")) {
                assertTrue(rs.next());
                assertEquals(10, rs.getInt(1), "Expected 10 sample records from V4 seed migration");
            }
        }
    }

    @Test
    @DisplayName("Should be idempotent when run multiple times on already-migrated database")
    void shouldBeIdempotentOnSubsequentRuns() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:migration_idempotent_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        dataSource.setUsername("sa");
        dataSource.setPassword("");

        DatabaseMigrationInitializer initializer = new DatabaseMigrationInitializer(dataSource);
        assertDoesNotThrow(initializer::runMigrations);
        // Second run must be clean no-op
        assertDoesNotThrow(initializer::runMigrations);
    }
}
