package com.bank.epricing.metrics;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  PricingMetrics.java — Custom Micrometer Metrics Definition             ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║  WHY CUSTOM METRICS:                                                     ║
 * ║  Spring Boot Actuator + Micrometer automatically collect:               ║
 * ║    - JVM metrics (heap, GC, threads)                                    ║
 * ║    - HTTP server metrics (request count, latency)                       ║
 * ║    - HikariCP metrics (connection pool)                                 ║
 * ║    - System metrics (CPU, disk)                                         ║
 * ║                                                                          ║
 * ║  But they DON'T collect BUSINESS metrics like:                          ║
 * ║    - "How many pricing calculations happened this hour?"                ║
 * ║    - "What's the approval rate for HOME_LOAN?"                          ║
 * ║    - "How many customers with credit score > 750 were served?"          ║
 * ║    - "What's the average loan amount requested today?"                  ║
 * ║                                                                          ║
 * ║  Custom metrics tell you if the BUSINESS is healthy, not just the JVM. ║
 * ║                                                                          ║
 * ║  MICROMETER METRIC TYPES:                                               ║
 * ║  Counter  → Only goes UP. Use for: requests, errors, events.           ║
 * ║  Gauge    → Can go UP or DOWN. Use for: queue size, active users.      ║
 * ║  Timer    → Measures duration + count. Use for: API latency, DB calls. ║
 * ║  Summary  → Distribution of values. Use for: loan amounts, scores.     ║
 * ║  DistributionSummary → Like Timer but for non-time values.             ║
 * ║                                                                          ║
 * ║  IMPLEMENTS MeterBinder:                                                 ║
 * ║  MeterBinder is the Spring-idiomatic way to register custom metrics.   ║
 * ║  bindTo(registry) is called by Spring when the MeterRegistry is ready. ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */
@Component
public class PricingMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(PricingMetrics.class);

    // ═══════════════════════════════════════════════════════════════════════
    // METRIC OBJECTS — declared as fields so they can be used throughout
    // the application lifetime (metrics persist for the app's life)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * COUNTER: Total number of pricing requests received.
     *
     * WHY Counter:
     * Request count ONLY ever goes up. You never "un-receive" a request.
     * In Prometheus/Grafana, you use rate() function to convert this
     * ever-increasing counter to a per-second rate:
     *   rate(pricing_requests_total[5m]) → requests per second over last 5 minutes
     *
     * TAGS (labels): Allow slicing the metric by dimension.
     *   pricing_requests_total{product_type="HOME_LOAN",status="SUCCESS"} 1250
     *   pricing_requests_total{product_type="PERSONAL_LOAN",status="FAILED"} 47
     *
     * WHY TAGS MATTER:
     * Without tags: "pricing_requests_total = 1297"
     * With tags: "HOME_LOAN succeeded 1250 times, PERSONAL_LOAN failed 47 times"
     * The second version lets you build alerts and dashboards that catch
     * failures in specific product categories — far more useful for ops teams.
     */
    private Counter totalPricingRequests;
    private Counter successfulPricingRequests;
    private Counter failedPricingRequests;
    private Counter rejectedPricingRequests;

    /**
     * TIMER: Measures how long pricing calculations take.
     *
     * WHY Timer (not Counter):
     * Timer records BOTH count AND duration.
     * From one Timer, Prometheus gets:
     *   pricing_calculation_duration_seconds_count → number of observations
     *   pricing_calculation_duration_seconds_sum   → total time spent
     *   pricing_calculation_duration_seconds_bucket → histogram buckets (for percentiles)
     *
     * In Grafana:
     *   rate(pricing_calculation_duration_seconds_sum[5m])
     *   / rate(pricing_calculation_duration_seconds_count[5m])
     *   = average calculation duration over last 5 minutes
     *
     * HISTOGRAM BUCKETS: With percentile histograms enabled (in application.yml),
     * you can query: histogram_quantile(0.95, pricing_calculation_duration_seconds_bucket)
     * → "95% of pricing calculations complete within X milliseconds"
     * This is your SLO (Service Level Objective) measurement.
     */
    private Timer pricingCalculationTimer;

    /**
     * TIMER: Full end-to-end request time including DB operations.
     * Compared with pricingCalculationTimer, the difference reveals
     * how much time is spent on DB I/O vs pure calculation.
     */
    private Timer endToEndRequestTimer;

    /**
     * GAUGE: Number of pricing requests currently being processed.
     *
     * WHY Gauge (not Counter):
     * Active request count goes UP when a request starts and DOWN when it ends.
     * Counters only go up. Gauges reflect current state.
     *
     * HIGH VALUE: If this gauge spikes and stays high, it means requests are
     * piling up (potential deadlock, DB timeout, external service hanging).
     * This triggers an alert before customers notice latency issues.
     *
     * AtomicLong: Thread-safe counter for concurrent request tracking.
     * AtomicLong.incrementAndGet() is non-blocking (compare-and-swap operation)
     * vs synchronized block which blocks all threads.
     */
    private final AtomicLong activePricingRequests = new AtomicLong(0);

    /**
     * DISTRIBUTION SUMMARY: Tracks the distribution of loan amounts.
     *
     * WHY DistributionSummary (not Timer):
     * Loan amounts are NOT time values. They're monetary values.
     * DistributionSummary is like Timer but for any numeric value.
     *
     * In Grafana:
     *   histogram_quantile(0.50, loan_amount_requested_rupees_bucket)
     *   → "Median loan amount requested is ₹X"
     *
     * This is a key business metric: "What loan amounts are customers requesting?"
     * If median suddenly drops from ₹30L to ₹5L → market signal.
     */
    private DistributionSummary loanAmountSummary;

    /**
     * COUNTER: Tracks product type distribution.
     * Used in a Grafana pie chart: "HOME_LOAN 45%, PERSONAL_LOAN 35%, ..."
     */
    private Counter homeLoanRequests;
    private Counter personalLoanRequests;
    private Counter businessLoanRequests;
    private Counter autoLoanRequests;
    private Counter educationLoanRequests;

    /**
     * bindTo() is called by Spring Boot automatically when the MeterRegistry
     * is initialized (during application startup, before any requests come in).
     *
     * @param registry The Micrometer MeterRegistry — the central hub where all
     *                 metrics are registered and managed.
     *                 In our case, this is backed by the Prometheus Registry
     *                 (because we added micrometer-registry-prometheus in pom.xml).
     */
    @Override
    public void bindTo(MeterRegistry registry) {
        log.info("Registering custom Micrometer metrics for ePricing service");

        // ─── COUNTERS ──────────────────────────────────────────────────────
        // Counter.builder(name).tag(key,value).description(text).register(registry)
        // The name becomes the Prometheus metric name (dots → underscores):
        // "pricing.requests.total" → "pricing_requests_total" in Prometheus

        this.totalPricingRequests = Counter.builder("pricing.requests.total")
            .description("Total number of pricing requests received")
            // "type" tag distinguishes this from other request counters
            // It appears in Prometheus as: pricing_requests_total{type="all"}
            .tag("type", "all")
            .register(registry);

        this.successfulPricingRequests = Counter.builder("pricing.requests.total")
            .description("Total number of successful pricing calculations")
            .tag("type", "success")
            .register(registry);

        this.failedPricingRequests = Counter.builder("pricing.requests.total")
            .description("Total number of failed pricing calculations")
            .tag("type", "failed")
            .register(registry);

        this.rejectedPricingRequests = Counter.builder("pricing.requests.total")
            .description("Total number of rejected pricing requests (business rules)")
            .tag("type", "rejected")
            .register(registry);

        // ─── TIMERS ────────────────────────────────────────────────────────
        // publishPercentileHistogram(true) → Stores histogram buckets in Prometheus
        // This is what enables histogram_quantile() queries in Grafana.
        // The trade-off: more data sent to Prometheus (higher cardinality).

        this.pricingCalculationTimer = Timer.builder("pricing.calculation.duration")
            .description("Time taken to calculate pricing (business logic only, excluding DB)")
            .tag("operation", "calculate")
            // Enable percentile histograms for p95/p99 SLO measurement
            .publishPercentileHistogram(true)
            // Manually define buckets that make sense for your business
            // These become the le (less-than-or-equal) labels in Prometheus
            .serviceLevelObjectives(
                java.time.Duration.ofMillis(50),
                java.time.Duration.ofMillis(100),
                java.time.Duration.ofMillis(200),
                java.time.Duration.ofMillis(500),
                java.time.Duration.ofSeconds(1)
            )
            .register(registry);

        this.endToEndRequestTimer = Timer.builder("pricing.request.end_to_end.duration")
            .description("Total end-to-end request processing time including DB operations")
            .tag("operation", "end_to_end")
            .publishPercentileHistogram(true)
            .register(registry);

        // ─── GAUGE ─────────────────────────────────────────────────────────
        // Gauge.builder reads the current value from the AtomicLong.
        // Every time Prometheus scrapes, it reads activePricingRequests.get()
        // to get the current in-flight request count.
        // The lambda () -> activePricingRequests.get() is the "observation function"

        Gauge.builder("pricing.requests.active", activePricingRequests, AtomicLong::get)
            .description("Number of pricing requests currently being processed (in-flight)")
            .tag("service", "epricing")
            .register(registry);

        // ─── DISTRIBUTION SUMMARY ──────────────────────────────────────────
        // scale(1): Loan amounts are already in Rupees. No scaling needed.
        // In a multi-currency system, you might normalize to one currency first.

        this.loanAmountSummary = DistributionSummary.builder("pricing.loan.amount.requested")
            .description("Distribution of loan amounts requested (in INR)")
            .baseUnit("rupees")
            .publishPercentileHistogram(true)
            .scale(1)  // Values are already in Rupees
            .register(registry);

        // ─── PRODUCT TYPE COUNTERS ─────────────────────────────────────────
        this.homeLoanRequests = Counter.builder("pricing.product.requests")
            .description("Pricing requests by product type")
            .tag("product", "HOME_LOAN").register(registry);

        this.personalLoanRequests = Counter.builder("pricing.product.requests")
            .description("Pricing requests by product type")
            .tag("product", "PERSONAL_LOAN").register(registry);

        this.businessLoanRequests = Counter.builder("pricing.product.requests")
            .description("Pricing requests by product type")
            .tag("product", "BUSINESS_LOAN").register(registry);

        this.autoLoanRequests = Counter.builder("pricing.product.requests")
            .description("Pricing requests by product type")
            .tag("product", "AUTO_LOAN").register(registry);

        this.educationLoanRequests = Counter.builder("pricing.product.requests")
            .description("Pricing requests by product type")
            .tag("product", "EDUCATION_LOAN").register(registry);

        log.info("Custom metrics registered successfully");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PUBLIC API — called by PricingService to record metrics
    // ═══════════════════════════════════════════════════════════════════════

    /** Call when ANY pricing request is received */
    public void recordPricingRequestReceived() {
        totalPricingRequests.increment();
    }

    /** Call when a pricing calculation succeeds */
    public void recordPricingSuccess() {
        successfulPricingRequests.increment();
    }

    /** Call when a pricing calculation fails (technical error) */
    public void recordPricingFailure() {
        failedPricingRequests.increment();
    }

    /** Call when a request is rejected (business rule — e.g., low credit score) */
    public void recordPricingRejection() {
        rejectedPricingRequests.increment();
    }

    /** Returns the Timer for recording calculation duration (used with try-with-resources) */
    public Timer.Sample startCalculationTimer() {
        // Timer.Sample.start() captures the start time.
        // Call sample.stop(timer) when the operation completes.
        return Timer.start();
    }

    /** Stop the calculation timer and record the duration */
    public void stopCalculationTimer(Timer.Sample sample) {
        sample.stop(pricingCalculationTimer);
    }

    /** Record end-to-end duration */
    public void recordEndToEndDuration(Timer.Sample sample) {
        sample.stop(endToEndRequestTimer);
    }

    /** Increment active requests when a request starts processing */
    public void incrementActiveRequests() {
        activePricingRequests.incrementAndGet();
    }

    /** Decrement active requests when a request finishes (success or failure) */
    public void decrementActiveRequests() {
        activePricingRequests.decrementAndGet();
    }

    /** Record the loan amount for distribution analysis */
    public void recordLoanAmount(double amountInRupees) {
        loanAmountSummary.record(amountInRupees);
    }

    /** Increment the counter for the given product type */
    public void recordProductTypeRequest(String productType) {
        switch (productType.toUpperCase()) {
            case "HOME_LOAN" -> homeLoanRequests.increment();
            case "PERSONAL_LOAN" -> personalLoanRequests.increment();
            case "BUSINESS_LOAN" -> businessLoanRequests.increment();
            case "AUTO_LOAN" -> autoLoanRequests.increment();
            case "EDUCATION_LOAN" -> educationLoanRequests.increment();
            default -> log.warn("Unknown product type for metrics: {}", productType);
        }
    }
}
