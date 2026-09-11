# Compliance Fixes — Learning Reference

> **Who this doc is for:** Engineers learning the codebase who want to understand *why* specific patterns exist and *what compliance gaps were found and fixed*.
>
> **Audit date:** September 2026

---

## What Is a Banking Compliance Audit?

When a bank builds software, it isn't just evaluated on whether it *works*. It also has to satisfy external reviewers:

- **Risk team** — can an attacker abuse the system? Can data be leaked?
- **Compliance team** — do we have a complete audit trail of every decision?
- **Regulators (RBI, SEBI)** — are customer data and financial decisions properly protected?

A compliance audit goes through the code systematically and checks each of these. This document records every issue found and fixed in this codebase.

---

## Issue 1 — Actuator Exposing Secrets

### What was wrong

Spring Boot Actuator is a built-in management API that exposes internal system info. The application had these endpoints exposed publicly on port 8081 with **no authentication**:

- `/actuator/env` — returns **all configuration properties including database password**
- `/actuator/configprops` — returns all Spring configuration values
- `/actuator/threaddump` — returns internal JVM thread state
- `/actuator/beans`, `/actuator/conditions` — internal Spring wiring

Anyone on the same network could call `http://host:8081/actuator/env` and read:
```json
{
  "DB_PASSWORD": "your-database-password",
  "DB_USERNAME": "your-database-user"
}
```

### What was fixed

`application.yml` was changed to only expose 4 safe endpoints:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
```

### The lesson

**Prometheus metrics (`/actuator/prometheus`) need to be public** — that's how Prometheus scrapes data. But `env`, `configprops`, and `threaddump` should **never** be accessible without authentication in any environment.

The general rule: expose the minimum surface area needed. Everything else should be denied.

---

## Issue 2 — Database Credentials with Safe Default

### What was wrong

```yaml
# Before — silently insecure
username: ${DB_USERNAME:yugabyte}
password: ${DB_PASSWORD:yugabyte}
```

The `:yugabyte` part is a **default value fallback**. If the `DB_USERNAME` and `DB_PASSWORD` environment variables are not set (e.g. a developer deployed to a new environment and forgot to configure secrets), the application would silently connect using `yugabyte / yugabyte` — the well-known factory default for YugabyteDB.

### What was fixed

```yaml
# After — fails loudly if secrets are missing
username: ${DB_USERNAME}
password: ${DB_PASSWORD}
```

Without a fallback, Spring Boot throws a startup error: `Could not resolve placeholder 'DB_USERNAME'`. The application **refuses to start** rather than silently using insecure credentials.

### The lesson

**Fail-fast is always better than silent fallback for secrets.** A system that crashes with a clear error is much safer than a system that silently operates with wrong/default credentials. With the silent fallback, you would only discover the problem after a security incident.

---

## Issue 3 — CORS Wildcard Default

### What is CORS?

CORS (Cross-Origin Resource Sharing) is a browser security feature. When JavaScript code running on `grafana.bank.com` tries to call `api.bank.com`, the browser blocks the request — unless the server says "yes, I allow requests from `grafana.bank.com`."

This prevents a malicious website (`evil.com`) from making API calls on behalf of a logged-in user.

### What was wrong

```yaml
# Before — wildcard fallback
cors:
  allowed-origins: ${CORS_ALLOWED_ORIGINS:*}
```

The default value was `*` — meaning **any origin** could make cross-origin requests. If a developer deployed without setting `CORS_ALLOWED_ORIGINS`, all CORS protection was bypassed.

### What was fixed

```yaml
# After — deny-all fallback
cors:
  allowed-origins: ${CORS_ALLOWED_ORIGINS:}
```

And `WebConfig.java` was updated to return early if the value is blank:

```java
if (corsAllowedOrigins == null || corsAllowedOrigins.isBlank()) {
    log.warn("CORS_ALLOWED_ORIGINS is not set — all cross-origin requests will be blocked.");
    return;  // No CORS mappings registered
}
```

### The lesson

**The secure default should always be "deny"**, not "allow all." This is called *fail-closed security*. CORS wildcard is especially dangerous for authenticated APIs because it allows any website to make API calls using the user's credentials.

---

## Issue 4 — Async Audit Logs

### What is the audit trail?

Every time a pricing calculation is made, two things happen:
1. The pricing result is saved to `pricing_requests` table
2. An audit log entry is saved to `pricing_audit_logs` table

The audit log is required by regulation — it's how the bank proves to the RBI that every decision was made correctly and can be reviewed.

### What was wrong

The audit methods had `@Async`:

```java
@Async("epricingAsyncExecutor")
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void recordSuccess(PricingRequest savedRequest, ...) { ... }
```

`@Async` means the method runs on a **background thread** — the caller doesn't wait for it. The response is sent to the client immediately, and the audit write happens later.

The problem: if the JVM crashes after sending the response but before the background thread writes the audit record, **the audit record is silently lost**. There is a window (usually milliseconds, but real) where the audit trail is incomplete.

### What was fixed

`@Async` was removed from `recordSuccess()` and `recordRejection()`:

```java
// No @Async — runs synchronously in the caller's thread
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void recordSuccess(PricingRequest savedRequest, ...) { ... }
```

`REQUIRES_NEW` is kept — this creates an independent transaction so the audit always commits even if the main service transaction rolls back. But it now runs in the same thread (synchronously) so there is no crash window.

`recordTechnicalFailure()` keeps `@Async` — it's called from an error handler where the main transaction is already failing, and it's better to attempt the audit in the background than to delay the error response.

### The lesson

**Async is a performance optimisation. Audit logging is a regulatory requirement.** These two things conflict. When they conflict, compliance wins. The small latency cost of a synchronous audit write is acceptable compared to the regulatory risk of a lost audit record.

---

## Issue 5 — PII in Logs

### What is PII?

PII = Personally Identifiable Information. In banking, this includes:
- Customer identifiers (even partial)
- Loan amounts (exact figures)
- Credit scores
- Annual income

### What was wrong

Log statements throughout the code contained raw PII:

```java
log.info("Pricing calculation started | customerId={} | amount={}", customerId, amount);
// → "customerId=CUST001234 | amount=5000000.00"
```

This data flows into Loki (the log storage). Anyone with Grafana access can search and read all customer financial data in plaintext.

### What was fixed

`StructuredLogger.java` now has two masking helpers:

```java
// Customer ID masking: "CUST001234" → "CUST****1234"
private String maskCustomerId(String customerId) {
    if (customerId == null || customerId.length() <= 4) return "****";
    return customerId.substring(0, 4) + "****" + customerId.substring(customerId.length() - 4);
}

// Amount bucketing: exact amounts replaced with range labels
// ₹50,000 → "<1L" | ₹5,00,000 → "1L-10L" | ₹50,00,000 → "10L-1Cr"
private String amountBracket(BigDecimal amount) {
    long val = amount.longValue();
    if (val < 100_000)    return "<1L";
    if (val < 1_000_000)  return "1L-10L";
    if (val < 10_000_000) return "10L-1Cr";
    return ">1Cr";
}
```

### The lesson

**Logs are for operations, not for data retrieval.** Logs should contain enough information to diagnose a problem (what operation, which customer category, what error) without containing the raw data itself. If you need the actual values, look in the database with proper access controls.

This also aligns with India's **DPDP Act 2023** (Digital Personal Data Protection Act), which requires purpose limitation on personal data — if the purpose of logs is operational monitoring, storing exact financial figures serves no monitoring purpose.

---

## Issue 6 — Demo Metrics Polluting Real Alerts

### What was wrong

`MetricsDemoController.simulateError()` incremented:

```java
meterRegistry.counter("pricing.errors.total", "error_type", "SIMULATED_ERROR").increment();
```

`pricing.errors.total` is the **exact same counter** that `GlobalExceptionHandler` uses for real errors. The Grafana alert rule fires when this counter exceeds a threshold.

In staging/UAT, running the demo endpoint would:
1. Increment `pricing.errors.total`
2. Trigger the real PagerDuty/OpsGenie alert
3. Wake someone up at 3 AM for a simulated error

### What was fixed

```java
// After — writes to demo-only counter
meterRegistry.counter("pricing.demo.errors.total", "error_type", "SIMULATED_ERROR").increment();
```

Demo traffic is now fully isolated in the `pricing.demo.*` metric namespace. Real alert rules query `pricing.errors.total` and are never affected by demo activity.

### The lesson

**Never share metric names between production and demo/test code.** Establish a namespace convention from day one:
- Real metrics: `pricing.*`
- Demo/load-test metrics: `pricing.demo.*`
- Test metrics: `pricing.test.*`

---

## Issue 7 — Client IP Behind Proxy

### What was wrong

```java
String clientIp = httpRequest.getRemoteAddr();
```

In production, the request flow is:
```
Client (203.0.113.1) → Load Balancer (10.0.0.1) → Application
```

`getRemoteAddr()` returns `10.0.0.1` — the load balancer's IP. Every audit record in the database shows the same internal IP, making forensic investigation impossible.

### What was fixed

The load balancer passes the original client IP in the `X-Forwarded-For` header:
```
X-Forwarded-For: 203.0.113.1, 10.0.0.1
```

The code now reads this header first:

```java
private String extractClientIp(HttpServletRequest request) {
    String xff = request.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isBlank() && !"unknown".equalsIgnoreCase(xff)) {
        return xff.split(",")[0].trim();  // Left-most = original client IP
    }
    String xRealIp = request.getHeader("X-Real-IP");
    if (xRealIp != null && !xRealIp.isBlank()) return xRealIp;
    return request.getRemoteAddr();  // Fallback for direct connections
}
```

Note: `MDCFilter.java` already had this logic — the fix aligned `PricingController` with it.

### The lesson

**Any production service behind a load balancer must read `X-Forwarded-For`**, not `getRemoteAddr()`. This is a very common beginner mistake. The `X-Forwarded-For` header can be spoofed by clients — always validate that it comes from a trusted proxy (your load balancer should be the only entity allowed to set this header at the network level).

---

## Issue 8 — Unused Enum Values

### What was wrong

```java
public enum PricingStatus {
    PENDING,    // ← never set anywhere
    CALCULATED,
    APPROVED,   // ← never set anywhere
    REJECTED,
    ERROR
}
```

`PENDING` and `APPROVED` were defined but no code ever set these values. They implied a two-step workflow (receive → process → approve) that didn't exist.

### What was fixed

```java
public enum PricingStatus {
    CALCULATED,  // Rate and EMI successfully computed
    REJECTED,    // Business rule rejection
    ERROR        // Technical error
}
```

A comment explains the scope boundary: approval is an LOS (Loan Origination System) concern, not a pricing engine concern.

### The lesson

**Unused code is misleading code.** A reviewer seeing `APPROVED` would reasonably ask "how does a request get approved?" — the answer being "it doesn't" wastes time and erodes trust in the design. Clean code means the code says exactly what the system does, nothing more.

---

## Issue 9 — Optional Annual Income / Silent FOIR Skip

### What is FOIR?

FOIR = Fixed Obligation to Income Ratio. RBI guidelines require that a borrower's total monthly loan repayments not exceed a certain percentage of their monthly income (typically 50-60%).

Formula:
```
FOIR = (Total monthly EMI obligations / Monthly income) × 100
If FOIR > 50% → reject the application
```

### What was wrong

```java
// @NotNull was missing
@DecimalMin(value = "100000.00", ...)
@JsonProperty("annual_income")
private BigDecimal annualIncome;  // Optional — no @NotNull
```

If a request arrived without `annual_income`, the FOIR check in `PricingCalculator.validateEligibility()` was silently skipped. The system would issue a rate without verifying income eligibility.

### What was fixed

```java
@NotNull(message = "Annual income is required for eligibility assessment")
@DecimalMin(value = "100000.00", ...)
@JsonProperty("annual_income")
private BigDecimal annualIncome;
```

Now any request without `annual_income` returns HTTP 400 with a clear validation error.

### The lesson

**If a business rule requires a field, make it required at the API layer too.** Allowing optional data that a required check depends on creates a hidden bypass. A client that omits `annual_income` isn't getting the right answer — they're getting a rate without a FOIR check, which is a regulatory violation.

---

## Issue 10 — Hardcoded Rate Validity Period

### What was wrong

```java
.rateValidUntil(LocalDateTime.now().plusDays(30))  // Hardcoded
```

30 days was hardcoded. In reality, the rate validity period is a business parameter set by the treasury/product team based on market conditions. It should not require a code change to adjust.

### What was fixed

```yaml
# application.yml
epricing:
  rate-valid-days: ${RATE_VALID_DAYS:7}
```

```java
// PricingService.java
@Value("${epricing.rate-valid-days:7}")
private int rateValidDays;

.rateValidUntil(LocalDateTime.now().plusDays(rateValidDays))
```

The default was also changed from 30 to 7 days — a tighter, more realistic validity window.

### The lesson

**Business parameters belong in configuration, not in code.** The rule of thumb: if a non-technical person (product manager, risk officer) might need to change a value, it should be in a config file or environment variable. Changing code requires a developer, a test cycle, and a deployment — that's too heavyweight for a business parameter.

---

## Summary: Banking-Grade Checklist

| Area | What to check | Fix pattern |
|---|---|---|
| Secrets | No defaults on credentials | `${SECRET_NAME}` not `${SECRET_NAME:default}` |
| Observability | Actuator endpoints restricted | Whitelist only what you need |
| CORS | Fail-closed default | Empty string = deny, not wildcard |
| Audit trail | Synchronous regulatory records | Remove `@Async` from audit writes |
| PII | Never in logs | Mask at the logger level |
| Metrics | Demo ≠ production | Separate namespaces |
| API | Real client IP | Read `X-Forwarded-For` |
| Data model | No dead code | Remove unused enum values |
| Validation | Required = `@NotNull` | If a check needs it, make it required |
| Config | No magic numbers | `@Value` with meaningful defaults |
