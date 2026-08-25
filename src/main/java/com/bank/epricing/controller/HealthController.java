package com.bank.epricing.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Custom health endpoint on the main port (8080).
 * Used by Kubernetes liveness probes and load balancer checks.
 * Complements /actuator/health on the management port (8081).
 */
@RestController
@RequestMapping("/health")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final DataSource dataSource;
    private final BuildProperties buildProperties;

    public HealthController(DataSource dataSource,
                            @org.springframework.beans.factory.annotation.Autowired(required = false)
                            BuildProperties buildProperties) {
        this.dataSource = dataSource;
        this.buildProperties = buildProperties;
    }

    /**
     * GET /health
     * Returns service status, DB connectivity, and JVM memory snapshot.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("service", "epricing-service");
        health.put("version", buildProperties != null ? buildProperties.getVersion() : "unknown");
        health.put("timestamp", LocalDateTime.now().toString());

        Map<String, String> db = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection()) {
            db.put("status", "UP");
            db.put("product", conn.getMetaData().getDatabaseProductName());
            db.put("version", conn.getMetaData().getDatabaseProductVersion());
        } catch (Exception e) {
            log.error("Database health check failed | error={}", e.getMessage());
            db.put("status", "DOWN");
            db.put("error", e.getMessage());
            health.put("status", "DEGRADED");
        }
        health.put("database", db);

        Map<String, Object> jvm = new LinkedHashMap<>();
        Runtime rt = Runtime.getRuntime();
        long usedMemory = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        long maxMemory = rt.maxMemory() / 1024 / 1024;
        jvm.put("heapUsedMb", usedMemory);
        jvm.put("heapMaxMb", maxMemory);
        jvm.put("heapUtilizationPct", String.format("%.1f%%", (double) usedMemory / maxMemory * 100));
        jvm.put("processors", rt.availableProcessors());
        health.put("jvm", jvm);

        health.put("endpoints", Map.of(
            "actuator", "http://localhost:8081/actuator",
            "prometheus", "http://localhost:8081/actuator/prometheus",
            "grafana", "http://localhost:3000",
            "yugabytedb_ui", "http://localhost:15433"
        ));

        log.info("Health check completed | status={}", health.get("status"));
        return ResponseEntity.ok(health);
    }
}
