package com.bank.epricing.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Comparator;

/**
 * DatabaseMigrationInitializer — Enterprise Distributed Schema Migration Runner.
 * Executes versioned SQL migrations (V1..Vn) sequentially and tracks applied scripts
 * in schema_history without requiring monolithic single-node PostgreSQL advisory locks.
 */
@Component
@Order(1)
public class DatabaseMigrationInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationInitializer.class);

    private final DataSource dataSource;

    public DatabaseMigrationInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(String... args) {
        log.info("Starting Enterprise Database Schema Migration Check...");
        try (Connection conn = dataSource.getConnection()) {
            // 1. Ensure schema_history table exists
            try (PreparedStatement stmt = conn.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS schema_history (" +
                    "  version VARCHAR(50) PRIMARY KEY," +
                    "  description VARCHAR(200) NOT NULL," +
                    "  script VARCHAR(200) NOT NULL," +
                    "  installed_on TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP" +
                    ")")) {
                stmt.execute();
            }

            // 2. Discover all migration scripts under db/migration/
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:db/migration/V*.sql");
            Arrays.sort(resources, Comparator.comparing(Resource::getFilename));

            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) continue;

                String version = filename.split("__")[0];
                String description = filename.substring(version.length() + 2).replace(".sql", "").replace("_", " ");

                // 3. Check if script was already applied
                boolean alreadyApplied = false;
                try (PreparedStatement checkStmt = conn.prepareStatement(
                        "SELECT COUNT(*) FROM schema_history WHERE version = ?")) {
                    checkStmt.setString(1, version);
                    try (ResultSet rs = checkStmt.executeQuery()) {
                        if (rs.next() && rs.getInt(1) > 0) {
                            alreadyApplied = true;
                        }
                    }
                }

                if (!alreadyApplied) {
                    log.info("Applying database migration: {} - {}", filename, description);
                    ScriptUtils.executeSqlScript(conn, resource);

                    try (PreparedStatement insertStmt = conn.prepareStatement(
                            "INSERT INTO schema_history (version, description, script) VALUES (?, ?, ?)")) {
                        insertStmt.setString(1, version);
                        insertStmt.setString(2, description);
                        insertStmt.setString(3, filename);
                        insertStmt.executeUpdate();
                    }
                    log.info("Successfully applied database migration: {}", filename);
                } else {
                    log.debug("Database migration already applied: {}", filename);
                }
            }
            log.info("Enterprise Database Schema Migrations are up to date.");
        } catch (Exception e) {
            log.error("CRITICAL: Database migration failed — application cannot start safely: {}", e.getMessage(), e);
            throw new RuntimeException("Database migration failed. Fix the migration scripts and restart.", e);
        }
    }
}
