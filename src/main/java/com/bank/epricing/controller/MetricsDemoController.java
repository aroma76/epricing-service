package com.bank.epricing.controller;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Dev/staging-only endpoints for triggering observability scenarios (slow requests, errors, load bursts).
 * Disabled in prod via @Profile("!prod") — these increment demo-namespaced counters only,
 * so they don't pollute real alert thresholds.
 */
@Profile("!prod")
@RestController
@RequestMapping("/metrics-demo")
public class MetricsDemoController {

    private static final Logger log = LoggerFactory.getLogger(MetricsDemoController.class);

    private final MeterRegistry meterRegistry;

    public MetricsDemoController(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** GET /metrics-demo — lists available metrics and their Prometheus names */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getMetricsSummary() {
        log.info("Metrics demo endpoint called | traceId={}", Span.current().getSpanContext().getTraceId());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("service", "epricing-service");
        summary.put("timestamp", LocalDateTime.now().toString());
        summary.put("traceId", Span.current().getSpanContext().getTraceId());
        summary.put("spanId", Span.current().getSpanContext().getSpanId());

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("actuator_prometheus_url", "http://localhost:8081/actuator/prometheus");
        metrics.put("key_metrics", List.of(
            "pricing_requests_total — Counter: total pricing requests",
            "pricing_calculation_duration_seconds — Timer: calculation time",
            "pricing_requests_active — Gauge: in-flight requests",
            "pricing_loan_amount_requested_rupees — DistributionSummary: loan distribution",
            "http_server_requests_seconds — Spring auto-metric: HTTP request latency",
            "jvm_memory_used_bytes — Spring auto-metric: JVM heap/non-heap",
            "hikaricp_connections_active — Spring auto-metric: DB connection pool"
        ));
        summary.put("metrics", metrics);

        Map<String, Object> grafanaInfo = new LinkedHashMap<>();
        grafanaInfo.put("url", "http://localhost:3000");
        grafanaInfo.put("default_credentials", "admin/admin");
        grafanaInfo.put("dashboards", List.of("JVM Overview", "Application Metrics", "Business Metrics"));
        summary.put("grafana", grafanaInfo);

        Map<String, Object> lokiInfo = new LinkedHashMap<>();
        lokiInfo.put("url", "http://localhost:3100");
        lokiInfo.put("example_query", "{application=\"epricing-service\"} | json | level=\"ERROR\"");
        summary.put("loki", lokiInfo);

        return ResponseEntity.ok(summary);
    }

    /** GET /metrics-demo/simulate-slow — adds a random 800-1500ms delay to spike p95 latency in Grafana */
    @GetMapping("/simulate-slow")
    public ResponseEntity<Map<String, Object>> simulateSlow() throws InterruptedException {
        long delay = 800 + (long)(Math.random() * 700);

        log.warn("Simulating slow operation | delay={}ms | traceId={}",
            delay, Span.current().getSpanContext().getTraceId());

        Span.current().setAttribute("simulation.type", "slow_operation");
        Span.current().setAttribute("simulation.delay_ms", delay);
        Span.current().addEvent("Starting intentional delay for demo");

        Thread.sleep(delay);

        Span.current().addEvent("Delay completed");
        meterRegistry.counter("pricing.demo.slow_requests", "type", "simulated").increment();

        return ResponseEntity.ok(Map.of(
            "message", "Slow operation completed. Check Grafana for latency spike.",
            "delay_ms", delay,
            "traceId", Span.current().getSpanContext().getTraceId(),
            "check_grafana", "http://localhost:3000 → Application Metrics → p95 Latency"
        ));
    }

    /**
     * GET /metrics-demo/simulate-error — fires an error log and increments pricing.demo.errors.total.
     * Uses demo namespace so it doesn't trigger real PagerDuty/OpsGenie alerts in staging.
     */
    @GetMapping("/simulate-error")
    public ResponseEntity<Map<String, Object>> simulateError() {
        log.error("Simulated error event | event_type=SIMULATED_ERROR | traceId={} | " +
                  "This is a deliberate error for observability demonstration",
            Span.current().getSpanContext().getTraceId());

        meterRegistry.counter(
            "pricing.demo.errors.total",
            "error_type", "SIMULATED_ERROR",
            "http_status", "500"
        ).increment();

        Span.current().setAttribute("simulation.type", "error");
        Span.current().setStatus(
            io.opentelemetry.api.trace.StatusCode.ERROR,
            "Simulated error for demo purposes"
        );

        return ResponseEntity.ok(Map.of(
            "message", "Error simulation complete. Check Grafana error rate and Loki ERROR logs.",
            "traceId", Span.current().getSpanContext().getTraceId(),
            "check_prometheus", "pricing_demo_errors_total{error_type=\"SIMULATED_ERROR\"}",
            "check_loki", "{application=\"epricing-service\"} | json | level=\"ERROR\""
        ));
    }

    /** GET /metrics-demo/generate-load — fires 50-100 counter increments across product types */
    @GetMapping("/generate-load")
    public ResponseEntity<Map<String, Object>> generateLoad() {
        int count = 50 + (int)(Math.random() * 50);

        log.info("Generating load for demo | count={}", count);

        for (int i = 0; i < count; i++) {
            String[] products = {"HOME_LOAN", "PERSONAL_LOAN", "BUSINESS_LOAN", "AUTO_LOAN"};
            String product = products[i % products.length];

            meterRegistry.counter("pricing.demo.load_test",
                "product_type", product,
                "status", i % 10 == 0 ? "FAILED" : "SUCCESS"
            ).increment();
        }

        return ResponseEntity.ok(Map.of(
            "message", "Load generated. Check rate(pricing_demo_load_test_total[1m]) in Grafana.",
            "incrementsGenerated", count,
            "check_prometheus", "http://localhost:9090/graph?g0.expr=rate(pricing_demo_load_test_total[1m])"
        ));
    }

    /** GET /metrics-demo/trace-demo — returns current trace context, useful for verifying OTel wiring */
    @GetMapping("/trace-demo")
    public ResponseEntity<Map<String, Object>> traceDemo() {
        Span currentSpan = Span.current();

        log.info("Trace demo | traceId={} | spanId={} | isSampled={}",
            currentSpan.getSpanContext().getTraceId(),
            currentSpan.getSpanContext().getSpanId(),
            currentSpan.getSpanContext().isSampled()
        );

        currentSpan.setAttribute("demo.custom_attribute", "Hello from ePricing!");
        currentSpan.setAttribute("demo.timestamp", System.currentTimeMillis());
        currentSpan.addEvent("trace-demo endpoint called");

        return ResponseEntity.ok(Map.of(
            "traceId", currentSpan.getSpanContext().getTraceId(),
            "spanId", currentSpan.getSpanContext().getSpanId(),
            "isSampled", currentSpan.getSpanContext().isSampled(),
            "isValid", currentSpan.getSpanContext().isValid(),
            "message", "Check Grafana Tempo for this trace: " + currentSpan.getSpanContext().getTraceId(),
            "grafana_tempo_url", "http://localhost:3000/explore (select Tempo datasource)"
        ));
    }
}
