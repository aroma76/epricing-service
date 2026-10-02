package com.bank.epricing.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HealthControllerTest {

    @Test
    @DisplayName("Should return HTTP 200 OK with status UP when database is reachable")
    void testHealth_DatabaseUp() {
        DataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:healthtest;DB_CLOSE_DELAY=-1", "sa", "");

        HealthController controller = new HealthController(dataSource, null);
        ResponseEntity<Map<String, Object>> response = controller.health();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().get("status"));
        assertNull(response.getBody().get("endpoints"), "Internal URLs must not be exposed");
    }

    @Test
    @DisplayName("Should return HTTP 503 SERVICE UNAVAILABLE with status DOWN when database fails")
    void testHealth_DatabaseDown() {
        DataSource failingDataSource = new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                throw new SQLException("Connection refused");
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                throw new SQLException("Connection refused");
            }
        };

        HealthController controller = new HealthController(failingDataSource, null);
        ResponseEntity<Map<String, Object>> response = controller.health();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("DOWN", response.getBody().get("status"));

        @SuppressWarnings("unchecked")
        Map<String, String> db = (Map<String, String>) response.getBody().get("database");
        assertEquals("DOWN", db.get("status"));
        assertNull(response.getBody().get("endpoints"), "Internal URLs must not be exposed");
    }
}
