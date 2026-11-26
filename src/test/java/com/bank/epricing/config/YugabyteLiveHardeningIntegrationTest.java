package com.bank.epricing.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live YugabyteDB 2.20 integration verification executed in an isolated ephemeral database.
 * Leaves the live epricingdb dataset completely untouched while proving real YSQL compatibility
 * for V1..V9 migrations, V9 idempotency unique index, and outbox_events DDL.
 */
class YugabyteLiveHardeningIntegrationTest {

    @Test
    @DisplayName("Executes V1..V9 schema migrations and enforces idempotency unique index + outbox constraints on live YugabyteDB")
    void testLiveYugabyteMigrationsAndConstraints() throws Exception {
        Assumptions.assumeTrue(isYugabyteReachable(), "YugabyteDB not reachable on localhost:5433 - skipping live YSQL test");

        String testDb = "epricing_verify_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String adminUrl = "jdbc:postgresql://localhost:5433/postgres";
        String testUser = System.getenv().getOrDefault("DB_USERNAME", "yugabyte");
        String testPass = System.getenv().getOrDefault("DB_PASSWORD", "yugabyte");

        try (Connection adminConn = DriverManager.getConnection(adminUrl, testUser, testPass);
             Statement adminStmt = adminConn.createStatement()) {
            adminStmt.execute("CREATE DATABASE " + testDb);
        }

        try {
            DriverManagerDataSource ds = new DriverManagerDataSource();
            ds.setDriverClassName("org.postgresql.Driver");
            ds.setUrl("jdbc:postgresql://localhost:5433/" + testDb);
            ds.setUsername(testUser);
            ds.setPassword(testPass);

            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("k8s");
            DatabaseMigrationInitializer initializer = new DatabaseMigrationInitializer(ds, false, "", env);
            assertDoesNotThrow(initializer::runMigrations);

            try (Connection conn = ds.getConnection();
                 Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pricing_requests")) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "V4 seed data must stay skipped in k8s/prod mode");
                }
                stmt.execute("INSERT INTO pricing_requests (customer_id, product_type, loan_amount, loan_tenure_months, status, idempotency_key) "
                        + "VALUES ('CUST-YB-001', 'HOME_LOAN', 5000000.00, 240, 'CALCULATED', 'yb-key-1')");
                assertThrows(Exception.class, () ->
                    stmt.execute("INSERT INTO pricing_requests (customer_id, product_type, loan_amount, loan_tenure_months, status, idempotency_key) "
                            + "VALUES ('CUST-YB-001', 'HOME_LOAN', 5000000.00, 240, 'CALCULATED', 'yb-key-1')"),
                    "Live YugabyteDB UNIQUE index idx_pricing_idempotency_key must reject duplicate keys");
                stmt.execute("INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, topic, message_key, payload) "
                        + "VALUES ('PRICING_REQUEST', 1, 'LOAN_PRICING_CALCULATED', 'loan-pricing-events', 'CUST-YB-001', '{}')");
            }
        } finally {
            try (Connection adminConn = DriverManager.getConnection(adminUrl, testUser, testPass);
                 Statement adminStmt = adminConn.createStatement()) {
                adminStmt.execute("DROP DATABASE IF EXISTS " + testDb);
            } catch (Exception ignored) {
                // Best effort cleanup of ephemeral test DB
            }
        }
    }

    private static boolean isYugabyteReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 5433), 400);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
