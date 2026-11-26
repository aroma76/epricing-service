package com.bank.epricing.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    @DisplayName("Should re-acquire connection when pg_advisory_lock is unsupported and pool closes connection")
    void shouldReacquireConnectionWhenAdvisoryLockClosesConnection() throws Exception {
        DriverManagerDataSource h2 = new DriverManagerDataSource();
        h2.setDriverClassName("org.h2.Driver");
        h2.setUrl("jdbc:h2:mem:migration_yugabyte_evict;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        h2.setUsername("sa");
        h2.setPassword("");

        // Pre-record PostgreSQL-specific V6 migration in schema_history so H2 executes all ANSI/DDL migrations cleanly
        try (Connection setupConn = h2.getConnection();
             Statement setupStmt = setupConn.createStatement()) {
            setupStmt.execute("CREATE TABLE IF NOT EXISTS schema_history (" +
                    "version VARCHAR(50) PRIMARY KEY, " +
                    "description VARCHAR(200) NOT NULL, " +
                    "script VARCHAR(200) NOT NULL, " +
                    "installed_on TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP)");
            setupStmt.execute("INSERT INTO schema_history (version, description, script) " +
                    "VALUES ('V6', 'create grafana reader role', 'V6__create_grafana_reader_role.sql')");
        }

        AtomicInteger getConnCalls = new AtomicInteger(0);
        DataSource simulatingDataSource = (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        int callNum = getConnCalls.incrementAndGet();
                        Connection realConn = h2.getConnection();
                        AtomicBoolean closedByPool = new AtomicBoolean(false);
                        return Proxy.newProxyInstance(
                                Connection.class.getClassLoader(),
                                new Class<?>[]{Connection.class},
                                (cProxy, cMethod, cArgs) -> {
                                    try {
                                        if ("isClosed".equals(cMethod.getName())) {
                                            return closedByPool.get() || realConn.isClosed();
                                        }
                                        if ("getMetaData".equals(cMethod.getName())) {
                                            DatabaseMetaData realMeta = realConn.getMetaData();
                                            return Proxy.newProxyInstance(
                                                    DatabaseMetaData.class.getClassLoader(),
                                                    new Class<?>[]{DatabaseMetaData.class},
                                                    (mProxy, mMethod, mArgs) -> {
                                                        try {
                                                            return "getDatabaseProductName".equals(mMethod.getName())
                                                                    ? "PostgreSQL"
                                                                    : mMethod.invoke(realMeta, mArgs);
                                                        } catch (InvocationTargetException ite) {
                                                            throw ite.getCause();
                                                        }
                                                    }
                                            );
                                        }
                                        if ("createStatement".equals(cMethod.getName()) && callNum == 1) {
                                            Statement realStmt = realConn.createStatement();
                                            return Proxy.newProxyInstance(
                                                    Statement.class.getClassLoader(),
                                                    new Class<?>[]{Statement.class},
                                                    (sProxy, sMethod, sArgs) -> {
                                                        try {
                                                            if ("execute".equals(sMethod.getName())
                                                                    && sArgs != null && sArgs.length == 1
                                                                    && String.valueOf(sArgs[0]).contains("pg_advisory_lock")) {
                                                                closedByPool.set(true);
                                                                realConn.close();
                                                                throw new SQLException("ERROR: advisory locks are not yet implemented", "0A000");
                                                            }
                                                            return sMethod.invoke(realStmt, sArgs);
                                                        } catch (InvocationTargetException ite) {
                                                            throw ite.getCause();
                                                        }
                                                    }
                                            );
                                        }
                                        if (closedByPool.get() && !"close".equals(cMethod.getName())) {
                                            throw new SQLException("Connection is closed");
                                        }
                                        return cMethod.invoke(realConn, cArgs);
                                    } catch (InvocationTargetException ite) {
                                        throw ite.getCause();
                                    }
                                }
                        );
                    }
                    try {
                        return method.invoke(h2, args);
                    } catch (InvocationTargetException ite) {
                        throw ite.getCause();
                    }
                }
        );

        DatabaseMigrationInitializer initializer = new DatabaseMigrationInitializer(simulatingDataSource);
        assertDoesNotThrow(initializer::runMigrations);
        assertEquals(2, getConnCalls.get(), "Expected fresh connection to be acquired after advisory lock failure evicted first connection");
    }

    @Test
    @DisplayName("Should skip V4 demo seed data when seedDemoData=false and still apply V9 schema hardening tables")
    void shouldSkipV4SeedDataWhenDisabledAndApplyV9Schema() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:migration_prod_skip_v4;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        dataSource.setUsername("sa");
        dataSource.setPassword("");

        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setActiveProfiles("prod");
        DatabaseMigrationInitializer initializer = new DatabaseMigrationInitializer(dataSource, false, "", env);
        assertDoesNotThrow(initializer::runMigrations);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pricing_requests")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "V4 dummy customer seed rows must NOT be inserted when seedDemoData=false in prod");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM outbox_events")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "V9 outbox_events table must exist");
            }
        }
    }
}
