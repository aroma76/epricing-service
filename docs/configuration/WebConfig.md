# WebConfig — CORS and Async Configuration

**Package:** `com.bank.epricing.config`
**File:** [`WebConfig.java`](../../src/main/java/com/bank/epricing/config/WebConfig.java)
**Annotations:** `@Configuration`, `@EnableAsync`

---

## Purpose

`WebConfig` serves two responsibilities:

1. **CORS Configuration** — allows browser-based clients (e.g., Grafana, Angular dashboards) to call the API from different origins.
2. **Async Thread Pool** — provides the named thread pool (`epricingAsyncExecutor`) used by `@Async` methods in `PricingAuditService`.

---

## CORS Configuration

```java
@Value("${epricing.cors.allowed-origins:*}")
private String corsAllowedOrigins;

@Override
public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/api/**")
        .allowedOriginPatterns(corsAllowedOrigins.split(","))
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

## Async Executor Configuration

```java
@Value("${epricing.async.core-pool-size:5}")
private int asyncCorePoolSize;

@Value("${epricing.async.max-pool-size:20}")
private int asyncMaxPoolSize;

@Value("${epricing.async.queue-capacity:100}")
private int asyncQueueCapacity;

@Value("${epricing.async.thread-name-prefix:epricing-async-}")
private String asyncThreadNamePrefix;

@Bean(name = "epricingAsyncExecutor")
public Executor epricingAsyncExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(asyncCorePoolSize);
    executor.setMaxPoolSize(asyncMaxPoolSize);
    executor.setQueueCapacity(asyncQueueCapacity);
    executor.setThreadNamePrefix(asyncThreadNamePrefix);
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    executor.initialize();
    return executor;
}
```

### Why Not Spring's Default Executor?

Spring's default `SimpleAsyncTaskExecutor`:
- Creates a **new OS thread** for every `@Async` call.
- Has **no upper bound** on thread count.
- Under load: thousands of threads → `OutOfMemoryError`.

`ThreadPoolTaskExecutor`:
- Maintains a pool of reusable threads.
- Bounded queue with configurable max threads.
- Predictable resource usage.

### Thread Pool Sizing Rationale

| Parameter | Value | Reasoning |
|---|---|---|
| `corePoolSize` | 5 | Threads always ready; I/O-bound audit writes |
| `maxPoolSize` | 20 | 2× CPU cores for I/O-bound work; handles burst |
| `queueCapacity` | 100 | Queue before spawning new threads above core |
| `awaitTerminationSeconds` | 30 | Graceful shutdown — no lost audit logs |

### Thread Naming

Threads are named `epricing-async-1`, `epricing-async-2`, etc. This makes audit-writing threads immediately identifiable in:
- JVM thread dumps (`/actuator/threaddump`)
- APM tools (visible in flame graphs)
- OS-level process monitors (`jstack`, VisualVM)

### Linking to @Async

The bean name `epricingAsyncExecutor` matches the annotation in `PricingAuditService`:

```java
@Async("epricingAsyncExecutor")
public void logAsync(PricingAuditLog auditLog) { ... }
```

If the names don't match, Spring falls back to the default executor (potentially `SimpleAsyncTaskExecutor`).

### @EnableAsync

```java
@Configuration
@EnableAsync
public class WebConfig implements WebMvcConfigurer { ... }
```

`@EnableAsync` activates Spring's asynchronous method execution support. Without this annotation, `@Async` methods execute **synchronously** — no error is thrown, they just don't run asynchronously. This is a common, hard-to-diagnose mistake.

---

## Cross-References

- [PricingAuditService.md](../services/PricingAuditService.md) — uses `epricingAsyncExecutor`
- [ApplicationConfig.md](./ApplicationConfig.md) — async pool settings in `application.yml`
