package com.bank.epricing.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  HealthController.java — Health, Liveness, and Readiness Endpoints       ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║  ISSUE-09 FIX: Decoupled Kubernetes Liveness Probe from external         ║
 * ║  database connectivity to prevent cascading pod restarts during DB blips║
 * ║                                                                          ║
 * ║  - GET /health/liveness: Checks internal JVM process health only         ║
 * ║    (memory, processors). Never acquires database connections.           ║
 * ║  - GET /health/readiness: Checks database connectivity to determine       ║
 * ║    whether the pod is ready to accept user traffic.                      ║
 * ║  - GET /health: Combined overview endpoint for backward compatibility.   ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */
@RestController
@RequestMapping("/health")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final DataSource dataSource;
    private final BuildProperties buildProperties;
    private final boolean exposeDetails;

    public HealthController(DataSource dataSource, BuildProperties buildProperties) {
        this(dataSource, buildProperties, false);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public HealthController(DataSource dataSource,
                            @org.springframework.beans.factory.annotation.Autowired(required = false)
                            BuildProperties buildProperties,
                            @org.springframework.beans.factory.annotation.Value("${epricing.health.expose-details:false}")
                            boolean exposeDetails) {
        this.dataSource = dataSource;
        this.buildProperties = buildProperties;
        this.exposeDetails = exposeDetails;
    }

    /**
     * GET /health/liveness
     * Kubernetes Liveness Probe: Checks only in-memory JVM health.
     * Must NEVER touch external dependencies like the database.
     */
    @GetMapping("/liveness")
    public ResponseEntity<Map<String, Object>> liveness() {
        Map<String, Object> liveness = new LinkedHashMap<>();
        liveness.put("status", "UP");
        liveness.put("timestamp", Instant.now().toString());
        liveness.put("jvm", getJvmMetrics());
        return ResponseEntity.ok(liveness);
    }

    /**
     * GET /health/readiness
     * Kubernetes Readiness Probe: Verifies database connectivity.
     * Pod is removed from service endpoints if DB is unavailable, without killing the container.
     */
    @GetMapping("/readiness")
    public ResponseEntity<Map<String, Object>> readiness() {
        Map<String, Object> readiness = new LinkedHashMap<>();
        readiness.put("service", "epricing-service");
        readiness.put("timestamp", Instant.now().toString());

        Map<String, String> db = checkDatabaseHealth();
        readiness.put("database", db);

        boolean dbHealthy = "UP".equals(db.get("status"));
        if (!dbHealthy) {
            readiness.put("status", "DOWN");
            readiness.put("reason", "Database connectivity unavailable");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(readiness);
        }

        readiness.put("status", "UP");
        return ResponseEntity.ok(readiness);
    }

    /**
     * GET /health
     * Returns application health with database connectivity check (backward compatibility).
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("service", "epricing-service");
        health.put("version", buildProperties != null ? buildProperties.getVersion() : "unknown");
        health.put("timestamp", Instant.now().toString());

        Map<String, String> db = checkDatabaseHealth();
        health.put("database", db);
        boolean dbHealthy = "UP".equals(db.get("status"));
        if (!dbHealthy) {
            health.put("status", "DOWN");
        }

        health.put("jvm", getJvmMetrics());

        log.info("Health check completed | status={}", health.get("status"));
        if (!dbHealthy) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(health);
        }
        return ResponseEntity.ok(health);
    }

    private Map<String, String> checkDatabaseHealth() {
        Map<String, String> db = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection()) {
            db.put("status", "UP");
            if (exposeDetails) {
                db.put("product", conn.getMetaData().getDatabaseProductName());
                db.put("version", conn.getMetaData().getDatabaseProductVersion());
            }
        } catch (Exception e) {
            log.error("Database health check failed | error={}", e.getMessage());
            db.put("status", "DOWN");
            if (exposeDetails) {
                db.put("error", e.getMessage());
            }
        }
        return db;
    }

    private Map<String, Object> getJvmMetrics() {
        Map<String, Object> jvm = new LinkedHashMap<>();
        Runtime rt = Runtime.getRuntime();
        long usedMemory = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        long maxMemory = rt.maxMemory() / 1024 / 1024;
        jvm.put("heapUsedMb", usedMemory);
        jvm.put("heapMaxMb", maxMemory);
        jvm.put("heapUtilizationPct", String.format("%.1f%%", (double) usedMemory / maxMemory * 100));
        jvm.put("processors", rt.availableProcessors());
        return jvm;
    }
}
