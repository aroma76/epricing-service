package com.bank.epricing.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Comparator;

/**
 * DatabaseMigrationInitializer — Enterprise Distributed Schema Migration Runner.
 * Executes versioned SQL migrations (V1..Vn) sequentially and tracks applied scripts
 * in schema_history. Implements InitializingBean and configures EntityManagerFactory
 * dependency to ensure migrations complete BEFORE Hibernate validates the schema.
 *
 * ISSUE-04 FIX: Uses PostgreSQL / YugabyteDB Session Advisory Lock (pg_advisory_lock)
 * to prevent multi-pod race conditions during concurrent rolling updates in Kubernetes.
 * Falls back gracefully on non-Postgres engines (e.g. H2 in unit/integration tests).
 *
 * ISSUE-11 FIX: Avoids hardcoded database names. Dynamically grants CONNECT to grafana_reader
 * using the runtime connection's active database catalog.
 */
@Component("databaseMigrationInitializer")
public class DatabaseMigrationInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationInitializer.class);
    private static final long MIGRATION_LOCK_ID = 718293849102L;

    private final DataSource dataSource;

    public DatabaseMigrationInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() {
        runMigrations();
    }

    public void runMigrations() {
        log.info("Starting Enterprise Database Schema Migration Check (pre-JPA initialization)...");
        try (Connection conn = dataSource.getConnection()) {
            String dbProduct = conn.getMetaData().getDatabaseProductName().toLowerCase();
            boolean isPostgresOrYugabyte = dbProduct.contains("postgres") || dbProduct.contains("yugabyte");
            boolean lockAcquired = false;

            if (isPostgresOrYugabyte) {
                log.info("Acquiring distributed PostgreSQL advisory lock ({}) for schema migration...", MIGRATION_LOCK_ID);
                try (Statement lockStmt = conn.createStatement()) {
                    lockStmt.execute("SELECT pg_advisory_lock(" + MIGRATION_LOCK_ID + ")");
                    lockAcquired = true;
                    log.info("Acquired distributed PostgreSQL advisory lock for schema migration.");
                }
            }

            try {
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
                Arrays.sort(resources, Comparator.comparingInt(r -> {
                    String name = r.getFilename();
                    if (name == null || !name.startsWith("V")) return 0;
                    try {
                        return Integer.parseInt(name.substring(1).split("__")[0]);
                    } catch (Exception e) {
                        return 0;
                    }
                }));

                for (Resource resource : resources) {
                    String filename = resource.getFilename();
                    if (filename == null)
                        continue;

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
                        if (filename.startsWith("V6")) {
                            if (!isPostgresOrYugabyte) {
                                log.info("Skipping V6 role migration on non-Postgres/Yugabyte database: {}", dbProduct);
                                try (PreparedStatement insertStmt = conn.prepareStatement(
                                        "INSERT INTO schema_history (version, description, script) VALUES (?, ?, ?)")) {
                                    insertStmt.setString(1, version);
                                    insertStmt.setString(2, description);
                                    insertStmt.setString(3, filename);
                                    insertStmt.executeUpdate();
                                }
                                continue;
                            }
                            // For PostgreSQL / YugabyteDB, ensure role exists without nested PL/pgSQL block
                            try (PreparedStatement checkRole = conn.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = 'grafana_reader'")) {
                                try (ResultSet rs = checkRole.executeQuery()) {
                                    if (!rs.next()) {
                                        try (Statement createStmt = conn.createStatement()) {
                                            createStmt.execute("CREATE ROLE grafana_reader WITH LOGIN PASSWORD 'grafana_reader_pass'");
                                            log.info("Created database role 'grafana_reader'");
                                        } catch (Exception createRoleEx) {
                                            log.warn("Could not create database role 'grafana_reader' (insufficient permissions or existing): {}", createRoleEx.getMessage());
                                        }
                                    }
                                }
                            }
                            // ISSUE-11 FIX: Dynamically grant CONNECT on current active database
                            String currentDb = conn.getCatalog();
                            if (currentDb != null && !currentDb.isBlank()) {
                                try (Statement grantStmt = conn.createStatement()) {
                                    grantStmt.execute("GRANT CONNECT ON DATABASE \"" + currentDb + "\" TO grafana_reader");
                                } catch (Exception ex) {
                                    log.warn("Could not grant CONNECT on database '{}' to grafana_reader: {}", currentDb, ex.getMessage());
                                }
                            }
                        }

                        log.info("Applying database migration: {} - {}", filename, description);
                        boolean originalAutoCommit = conn.getAutoCommit();
                        try {
                            conn.setAutoCommit(false);
                            ScriptUtils.executeSqlScript(conn, resource);

                            try (PreparedStatement insertStmt = conn.prepareStatement(
                                    "INSERT INTO schema_history (version, description, script) VALUES (?, ?, ?)")) {
                                insertStmt.setString(1, version);
                                insertStmt.setString(2, description);
                                insertStmt.setString(3, filename);
                                insertStmt.executeUpdate();
                            }
                            conn.commit();
                            log.info("Successfully applied database migration: {}", filename);
                        } catch (Exception ex) {
                            conn.rollback();
                            throw ex;
                        } finally {
                            conn.setAutoCommit(originalAutoCommit);
                        }
                    } else {
                        log.debug("Database migration already applied: {}", filename);
                    }
                }
                log.info("Enterprise Database Schema Migrations are up to date.");
            } finally {
                if (lockAcquired) {
                    try (Statement unlockStmt = conn.createStatement()) {
                        unlockStmt.execute("SELECT pg_advisory_unlock(" + MIGRATION_LOCK_ID + ")");
                        log.info("Released distributed PostgreSQL advisory lock ({}).", MIGRATION_LOCK_ID);
                    } catch (Exception unlockEx) {
                        log.warn("Failed to release PostgreSQL advisory lock: {}", unlockEx.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("CRITICAL: Database migration failed — application cannot start safely: {}", e.getMessage(), e);
            throw new RuntimeException("Database migration failed. Fix the migration scripts and restart.", e);
        }
    }

    @Component
    public static class DatabaseMigrationJpaDependencyConfig implements BeanFactoryPostProcessor {
        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
            String[] entityManagerFactoryNames = beanFactory
                    .getBeanNamesForType(jakarta.persistence.EntityManagerFactory.class, true, false);
            for (String emfName : entityManagerFactoryNames) {
                org.springframework.beans.factory.config.BeanDefinition bd = beanFactory.getBeanDefinition(emfName);
                String[] dependsOn = bd.getDependsOn();
                if (dependsOn == null || dependsOn.length == 0) {
                    bd.setDependsOn("databaseMigrationInitializer");
                } else {
                    String[] newDependsOn = Arrays.copyOf(dependsOn, dependsOn.length + 1);
                    newDependsOn[dependsOn.length] = "databaseMigrationInitializer";
                    bd.setDependsOn(newDependsOn);
                }
            }
        }
    }
}
