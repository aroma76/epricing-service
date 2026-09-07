# PricingMetrics — Custom Micrometer Metrics

**Package:** `com.bank.epricing.metrics`
**File:** [`PricingMetrics.java`](../../src/main/java/com/bank/epricing/metrics/PricingMetrics.java)
**Stereotype:** `@Component`, implements `MeterBinder`

---

## Purpose

`PricingMetrics` defines and registers **custom business metrics** that Spring Boot Actuator's auto-instrumentation does not provide. While JVM metrics, HTTP server metrics, and HikariCP metrics are automatic, business-level questions require custom metrics:

- "How many pricing calculations happened this hour?"
- "What's the approval rate for HOME_LOAN?"
- "What's the 95th percentile pricing calculation latency?"

---

## MeterBinder Pattern

```java
@Component
public class PricingMetrics implements MeterBinder {
    @Override
    public void bindTo(MeterRegistry registry) {
        // register all metrics here
    }
}
```

`MeterBinder.bindTo(registry)` is called by Spring at startup when the `MeterRegistry` is ready. All metric objects are registered once and reused throughout the application lifetime.

---

## Metric Definitions

### Counters

Counters monotonically increase. Use `rate()` in PromQL to convert to per-second rates.

#### `pricing.requests.total{type="all|success|failed|rejected"}`

| Tag value | Method | Description |
|---|---|---|
| `all` | `recordPricingRequestReceived()` | Every incoming pricing request |
| `success` | `recordPricingSuccess()` | Successfully calculated pricing |
| `failed` | `recordPricingFailure()` | Technical/system error |
| `rejected` | `recordPricingRejection()` | Business rule rejection |

**Prometheus format:**
```
pricing_requests_total{type="all",application="epricing-service"} 1297.0
pricing_requests_total{type="success",...} 1250.0
pricing_requests_total{type="rejected",...} 47.0
```

**Grafana PromQL — Error rate:**
```promql
(
  rate(pricing_requests_total{type="failed"}[5m])
  / rate(pricing_requests_total{type="all"}[5m])
) * 100
```

---

#### `pricing.product.requests{product="HOME_LOAN|PERSONAL_LOAN|..."}` 

| Tag value | Method |
|---|---|
| `HOME_LOAN` | `recordProductTypeRequest("HOME_LOAN")` |
| `PERSONAL_LOAN` | `recordProductTypeRequest("PERSONAL_LOAN")` |
| `BUSINESS_LOAN` | `recordProductTypeRequest("BUSINESS_LOAN")` |
| `AUTO_LOAN` | `recordProductTypeRequest("AUTO_LOAN")` |
| `EDUCATION_LOAN` | `recordProductTypeRequest("EDUCATION_LOAN")` |

**Grafana use:** Pie chart showing distribution of product types.

---

### Timers

#### `pricing.calculation.duration{operation="calculate"}`

Measures pure calculation time (business logic only, excluding DB I/O).

**Configuration:**
```java
Timer.builder("pricing.calculation.duration")
    .publishPercentileHistogram(true)
    .serviceLevelObjectives(
        Duration.ofMillis(50),
        Duration.ofMillis(100),
        Duration.ofMillis(200),
        Duration.ofMillis(500),
        Duration.ofSeconds(1)
    )
    .register(registry);
```

- `publishPercentileHistogram(true)`: Stores histogram buckets in Prometheus, enabling `histogram_quantile()` queries.
- SLO buckets create `le` labels in Prometheus: `pricing_calculation_duration_seconds_bucket{le="0.1"}`.

**Prometheus output (three time series per Timer):**
```
pricing_calculation_duration_seconds_count 1297
pricing_calculation_duration_seconds_sum   89.34
pricing_calculation_duration_seconds_bucket{le="0.05"} 1100
```

**Grafana PromQL — P95 latency:**
```promql
histogram_quantile(0.95,
  rate(pricing_calculation_duration_seconds_bucket[5m])
)
```

**Usage pattern:**
```java
Timer.Sample sample = metrics.startCalculationTimer();
try {
    // ... do calculation ...
} finally {
    metrics.stopCalculationTimer(sample);
}
```

---

#### `pricing.request.end_to_end.duration{operation="end_to_end"}`

Measures total request time including DB operations. Comparing this with `pricing.calculation.duration` reveals how much time is spent on DB I/O vs. pure computation.

---

### Gauge

#### `pricing.requests.active{service="epricing"}`

Current number of in-flight pricing requests.

```java
private final AtomicLong activePricingRequests = new AtomicLong(0);

Gauge.builder("pricing.requests.active", activePricingRequests, AtomicLong::get)
    .register(registry);
```

`AtomicLong` is used for thread safety — `incrementAndGet()` is a lock-free CAS (compare-and-swap) operation.

**High value:** If this gauge spikes and stays high, requests are piling up — possible deadlock, DB timeout, or external service hang.

---

### Distribution Summary

#### `pricing.loan.amount.requested{unit="rupees"}`

Tracks the distribution of loan amounts requested.

```java
DistributionSummary.builder("pricing.loan.amount.requested")
    .baseUnit("rupees")
    .publishPercentileHistogram(true)
    .scale(1)
    .register(registry);
```

**Grafana PromQL — Median loan amount:**
```promql
histogram_quantile(0.50, pricing_loan_amount_requested_rupees_bucket)
```

**Business insight:** "If median loan amount suddenly drops from ₹30L to ₹5L → potential market signal or data quality issue."

---

## Global Tags from application.yml

```yaml
management:
  metrics:
    tags:
      application: epricing-service
      team: epricing
      region: india
      environment: ${spring.profiles.active}
```

These tags are added to **all** metrics — auto-instrumented and custom. They enable:
- Filtering across multiple deployed instances
- Multi-region comparison in Grafana
- Environment-specific alerting rules

---

## Automatic Metrics (No Code Required)

In addition to custom metrics, the following are auto-instrumented:

| Metric Prefix | Source |
|---|---|
| `http_server_requests_seconds_*` | Spring MVC auto-instrumentation |
| `jvm_memory_*` | JVM GC/heap metrics |
| `jvm_gc_*` | Garbage collection |
| `jvm_threads_*` | Thread pool metrics |
| `hikaricp_connections_*` | HikariCP connection pool |
| `process_cpu_usage` | JVM CPU usage |
| `system_cpu_usage` | System CPU usage |
| `disk_free_bytes` | Disk space |
| `tomcat_threads_*` | Embedded Tomcat thread pool |

---

## Cross-References

- [PricingService.md](../services/PricingService.md) — calls metric recording methods
- [Prometheus.md](./Prometheus.md) — Prometheus scrape configuration
- [Grafana.md](./Grafana.md) — PromQL queries in dashboards
- [AlertingRules.md](./AlertingRules.md) — alerts based on these metrics
