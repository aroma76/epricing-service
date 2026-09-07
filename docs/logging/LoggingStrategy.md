# Logging Strategy

**Package:** `com.bank.epricing.logging`
**Files:**
- [`MDCFilter.java`](../../src/main/java/com/bank/epricing/logging/MDCFilter.java)
- [`StructuredLogger.java`](../../src/main/java/com/bank/epricing/logging/StructuredLogger.java)
- [`logback-spring.xml`](../../src/main/resources/logback-spring.xml)

---

## Overview

The logging strategy is built on three layers working together to produce **queryable, correlated, structured log events** that are searchable in Grafana Loki:

1. **`logback-spring.xml`** — Configures Logback to output JSON via `LogstashEncoder`.
2. **`MDCFilter`** — A servlet filter that populates MDC (Mapped Diagnostic Context) with per-request context.
3. **`StructuredLogger`** — Typed log event methods with consistent field names.

---

## Layer 1: logback-spring.xml

### Why JSON Logs?

Plain text logs:
```
2024-01-15 10:30:00 INFO  PricingService - Pricing requested by CUST001234
```

JSON logs (after LogstashEncoder):
```json
{
  "@timestamp": "2024-01-15T10:30:00.000+05:30",
  "level": "INFO",
  "logger_name": "com.bank.epricing.service.PricingService",
  "message": "Pricing calculation started",
  "traceId": "abc123def456",
  "spanId": "789xyz",
  "customerId": "CUST001234",
  "requestId": "REQ-ABC123DEF456",
  "application": "epricing-service",
  "team": "epricing",
  "environment": "docker"
}
```

JSON logs are machine-parseable by Loki, enabling Grafana queries like:
```
{application="epricing-service"} | json | event_type="PRICING_STARTED" | customerId="CUST001234"
```

### Appenders

| Appender | Class | Purpose |
|---|---|---|
| `CONSOLE` | `ConsoleAppender` | Stdout — captured by Docker and Kubernetes |
| `FILE` | `RollingFileAppender` | Disk file — read by Promtail |
| `ASYNC_FILE` | `AsyncAppender` | Wraps `FILE` with in-memory queue |

### Rolling File Policy

```xml
<rollingPolicy class="SizeAndTimeBasedRollingPolicy">
  <fileNamePattern>./logs/archived/epricing-service.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
  <maxFileSize>100MB</maxFileSize>
  <maxHistory>30</maxHistory>
  <totalSizeCap>3GB</totalSizeCap>
</rollingPolicy>
```

- Rotates daily and when file exceeds 100MB.
- Keeps 30 days of history with `.gz` compression.
- Total cap of 3GB — prevents disk exhaustion on containers.

### Async Appender

```xml
<appender name="ASYNC_FILE" class="AsyncAppender">
  <queueSize>512</queueSize>
  <discardingThreshold>20</discardingThreshold>
  <maxFlushTime>30000</maxFlushTime>
</appender>
```

- `queueSize=512`: In-memory buffer for log events.
- `discardingThreshold=20`: When queue is 80% full, drop `TRACE`/`DEBUG` logs to protect `WARN`/`ERROR` throughput.
- `maxFlushTime=30000`: On shutdown, flush queue for up to 30 seconds.

### Profile-Based Configuration

| Profile | File Appender | SQL Logging | App Log Level |
|---|---|---|---|
| `local`, `dev` | Console only | `DEBUG` (Hibernate SQL) | `DEBUG` |
| `prod`, `docker`, `staging` | Console + ASYNC_FILE | `OFF` | `INFO` |

---

## Layer 2: MDCFilter

**File:** [`MDCFilter.java`](../../src/main/java/com/bank/epricing/logging/MDCFilter.java)
**Annotation:** `@Order(1)` — runs before all other filters

### What is MDC?

MDC (Mapped Diagnostic Context) is a thread-local key-value map provided by SLF4J. Every value placed in MDC is **automatically included in every log statement on that thread** — no need to pass context through method calls.

### Keys Populated per Request

| MDC Key | Source | Example Value |
|---|---|---|
| `requestId` | `X-Request-ID` header, or generated UUID | `REQ-ABC123DEF456` |
| `customerId` | `X-Customer-ID` header | `CUST001234` |
| `clientIp` | `X-Forwarded-For` or `getRemoteAddr()` | `203.0.113.195` |
| `httpMethod` | `request.getMethod()` | `POST` |
| `requestUri` | `request.getRequestURI()` | `/api/v1/pricing` |
| `traceId` | Auto-populated by Micrometer Tracing | `abc123def456` |
| `spanId` | Auto-populated by Micrometer Tracing | `789xyz` |

### MDC Lifecycle

```
Request arrives →
  MDCFilter.doFilter():
    1. Extract/generate requestId
    2. MDC.put(requestId, ...)
    3. MDC.put(clientIp, ...)
    4. MDC.put(httpMethod, ...)
    5. filterChain.doFilter() → controller → service → (all logs include MDC)
  finally:
    6. MDC.clear()  ← CRITICAL: prevents MDC leak on thread reuse
```

**Why `MDC.clear()` in `finally`?** Tomcat uses a thread pool. Without clearing MDC, the next request served by the same thread inherits the previous request's `customerId`, `requestId`, etc. This is both a data privacy concern and a debugging nightmare.

### X-Request-ID Propagation

```java
response.setHeader("X-Request-ID", requestId);
```

The `requestId` is echoed back in the response header. Clients can reference this ID in support tickets for end-to-end correlation.

### Client IP Extraction

```java
private String getClientIpAddress(HttpServletRequest request) {
    String xForwardedFor = request.getHeader("X-Forwarded-For");
    if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
        return xForwardedFor.split(",")[0].trim();  // Leftmost = original client
    }
    String xRealIp = request.getHeader("X-Real-IP");
    if (xRealIp != null && !xRealIp.isEmpty()) {
        return xRealIp;
    }
    return request.getRemoteAddr();  // Fallback: socket IP (may be load balancer)
}
```

---

## Layer 3: StructuredLogger

**File:** [`StructuredLogger.java`](../../src/main/java/com/bank/epricing/logging/StructuredLogger.java)
**Stereotype:** `@Component`

Provides typed, named log event methods. Instead of scattered `log.info("...")` calls, all business events go through `StructuredLogger` for consistent field names.

### Methods

| Method | Level | `event_type` | When called |
|---|---|---|---|
| `logPricingStarted(customerId, productType, amount)` | `INFO` | `PRICING_STARTED` | Before pricing calculation begins |
| `logPricingCompleted(customerId, productType, rate, emi, ms)` | `INFO` | `PRICING_COMPLETED` | After successful calculation |
| `logPricingRejected(customerId, productType, reason, code)` | `WARN` | `PRICING_REJECTED` | Business rule rejection |
| `logPricingError(customerId, productType, message, cause)` | `ERROR` | `PRICING_ERROR` | Technical error |
| `logAuditEvent(action, by, target, outcome)` | `INFO` | `AUDIT_EVENT` | Regulatory audit events |
| `logSlowOperation(operation, durationMs, context)` | `WARN` | `SLOW_OPERATION` | Operations > 500ms |
| `logValidationFailure(path, fieldErrors)` | `WARN` | `VALIDATION_FAILURE` | DTO validation failures |
| `logDatabaseError(operation, entity, message, cause)` | `ERROR` | `DATABASE_ERROR` | DB operation failures |
| `logDatabaseOperation(operation, entity, durationMs, success)` | `DEBUG`/`WARN` | `DB_OPERATION` | DB performance tracking |

### OTel TraceId Inclusion

`logPricingCompleted` explicitly reads the current OTel trace ID:

```java
String traceId = Span.current().getSpanContext().getTraceId();
log.info("... | traceId={}", traceId);
```

This manual inclusion acts as a safety net — even if the `logback-spring.xml` MDC configuration is missing, the trace ID is still present in the log message.

### Slow Database Operation Detection

```java
public void logDatabaseOperation(String operation, String entity, long durationMs, boolean success) {
    if (durationMs > 200) {
        log.warn("Slow database operation | event_type=SLOW_DB_OPERATION | ...", ...);
    } else {
        log.debug("Database operation | ...", ...);
    }
}
```

DB operations exceeding 200ms are automatically elevated from `DEBUG` to `WARN`. This feeds the Grafana "Slow Queries" panel without requiring manual threshold configuration in each call site.

---

## Log Levels per Environment

| Logger | local/dev | prod/docker |
|---|---|---|
| `com.bank` (all app code) | `DEBUG` | `INFO` |
| `org.springframework` | `INFO` | `WARN` |
| `org.hibernate` | `INFO` | `WARN` |
| `org.hibernate.SQL` | `DEBUG` (shows SQL) | `OFF` |
| `org.hibernate.type.descriptor.sql` | `TRACE` (shows bind params) | `OFF` |

SQL logging is disabled in production for:
- **Security**: SQL logs may contain customer data (PII).
- **Performance**: `TRACE` logging is CPU-intensive at high volume.

---

## Grafana Loki Query Examples

After logs are shipped by Promtail to Loki:

```logql
# All errors in the last 1 hour
{application="epricing-service"} | json | level="ERROR"

# All logs for a specific customer
{application="epricing-service"} | json | customerId="CUST001234"

# All logs for a specific trace (cross-reference with Tempo)
{application="epricing-service"} | json | traceId="abc123def456"

# Slow operations
{application="epricing-service"} | json | event_type="SLOW_OPERATION"

# Pricing rejections in the last 30 minutes
{application="epricing-service"} | json | event_type="PRICING_REJECTED"
```

---

## Cross-References

- [Loki.md](../monitoring/Loki.md) — log storage and querying
- [OpenTelemetry.md](../monitoring/OpenTelemetry.md) — traceId source
- [PricingService.md](../services/PricingService.md) — uses StructuredLogger
- [ExceptionHandling.md](../exception-handling/ExceptionHandling.md) — error log events
