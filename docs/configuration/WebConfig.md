# WebConfig — CORS Configuration & Async Modernization

**Package:** `com.bank.epricing.config`  
**File:** [`WebConfig.java`](file:///c:/Users/Sonux/Desktop/epricing-service/src/main/java/com/bank/epricing/config/WebConfig.java)  
**Annotations:** `@Configuration`

---

## Purpose

`WebConfig` provides:

1. **CORS Configuration** — allows browser-based clients (e.g., Grafana, Angular/React dashboards) to call the API from configured origins securely.
2. **Lean Architecture** — Asynchronous orchestration, rate locks, and durable task execution are now managed by **Temporal** (`LoanPricingWorkflow`, `PricingActivities`), replacing redundant in-JVM `@Async` thread pool boilerplate.

---

## CORS Configuration

```java
@Value("${epricing.cors.allowed-origins:}")
private String corsAllowedOrigins;

@Override
public void addCorsMappings(CorsRegistry registry) {
    if (corsAllowedOrigins == null || corsAllowedOrigins.isBlank()) {
        log.warn("CORS_ALLOWED_ORIGINS is not set — all cross-origin requests will be blocked.");
        return;
    }
    String[] origins = corsAllowedOrigins.split(",");
    registry.addMapping("/api/**")
        .allowedOriginPatterns(origins)
        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
        .allowedHeaders("Content-Type", "Authorization", "X-Request-ID",
                        "X-Customer-ID", "X-Correlation-ID")
        .exposedHeaders("X-Request-ID", "X-Trace-ID") // headers clients can read
        .allowCredentials(false)
        .maxAge(3600);                                 // Browser caches preflight for 1 hour
}
```

CORS is required when browser JavaScript on one origin (e.g., `http://grafana.bank.com:3000`) calls an API on another origin (`http://api.bank.com:8080`). Without CORS headers, the browser blocks the request.

**Custom headers allowed:**
- `X-Request-ID` — client-provided request ID for correlation
- `X-Customer-ID` — customer context (used by `MDCFilter`)
- `X-Correlation-ID` — distributed correlation ID

**Custom headers exposed:**
- `X-Request-ID` — echoed back from `MDCFilter` (clients can read this)
- `X-Trace-ID` — OTel trace ID for client-side cross-referencing

> **Production Security:** Allowed origins are configurable via the `CORS_ALLOWED_ORIGINS` environment variable (mapped to `epricing.cors.allowed-origins`). In production, set this to comma-separated explicit domains:
> ```bash
> CORS_ALLOWED_ORIGINS=https://portal.bank.com,https://grafana.bank.internal
> ```
> This prevents unrestricted cross-origin requests while avoiding hardcoded origins in code.

---

## Modernization: Transition from Spring `@Async` to Temporal

Historically, Spring Boot applications used `@EnableAsync` and custom `ThreadPoolTaskExecutor` beans (`epricingAsyncExecutor`) to offload background tasks. However, in-JVM thread pools have major production drawbacks in banking:
- **Thread Exhaustion & Memory Risk**: Spikes require large thread queues; crashes lose queued in-memory tasks permanently.
- **No Durable State**: Pausing or waiting for external events (such as rate-lock expirations or customer approvals) consumes thread resources or requires complex database polling tables.
- **No Native Distributed Retries**: Retries must be coded manually.

With **Temporal Workflows & Activities**:
- Workflows are **durable and deterministic**; rate locks, approvals, and timers sleep in Temporal without holding any OS/JVM threads.
- Activities (`PricingActivitiesImpl`) execute calculation and audit logging with automatic exponential backoff and transparent state recovery.
- Custom Spring thread pools (`ThreadPoolTaskExecutor`) and `@Async` boilerplate are eliminated, making the service codebase compact and resilient.

---

## Cross-References

- [PricingActivitiesImpl.java](file:///c:/Users/Sonux/Desktop/epricing-service/src/main/java/com/bank/epricing/temporal/activity/PricingActivitiesImpl.java) — durable Temporal activities
- [LoanPricingWorkflow.java](file:///c:/Users/Sonux/Desktop/epricing-service/src/main/java/com/bank/epricing/temporal/workflow/LoanPricingWorkflow.java) — Temporal pricing workflow
- [PricingAuditService.java](file:///c:/Users/Sonux/Desktop/epricing-service/src/main/java/com/bank/epricing/service/PricingAuditService.java) — audit persistence logic
