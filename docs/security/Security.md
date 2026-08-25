# Security

> **Authentication status: Not yet implemented** — all other controls below are implemented.

Authentication and authorization are **not in scope for this POC version**. All other security controls described here are **active in the codebase**.

---

## Implemented Security Controls

| Control | Status | File |
|---|---|---|
| Stack trace suppression in API errors | ✅ Implemented | `application.yml` — `include-stacktrace: never` |
| Non-root container user | ✅ Implemented | `Dockerfile` — `USER epricing` |
| Read-only config volume mounts | ✅ Implemented | `docker-compose.yml` — `:ro` mounts |
| Grafana anonymous access disabled | ✅ Implemented | `docker-compose.yml` — `GF_AUTH_ANONYMOUS_ENABLED: "false"` |
| No sensitive fields in API response | ✅ Implemented | `PricingResponseDto` excludes internal entity fields |
| Actuator restricted to safe endpoints only | ✅ Implemented | `application.yml` — only `health, info, prometheus, metrics` |
| DB credentials fail-fast (no defaults) | ✅ Implemented | `application.yml` — `${DB_USERNAME}` with no fallback |
| CORS deny-by-default | ✅ Implemented | `WebConfig.java` — blocks all origins if `CORS_ALLOWED_ORIGINS` not set |
| PII masked in all logs | ✅ Implemented | `StructuredLogger.java` — customer ID masked, amounts bracketed |
| Audit logs synchronous (cannot be lost) | ✅ Implemented | `PricingAuditService.java` — `recordSuccess()` and `recordRejection()` are synchronous |
| Real client IP captured (proxy-aware) | ✅ Implemented | `PricingController.java` — reads `X-Forwarded-For` before `getRemoteAddr()` |
| Demo metrics isolated from real alerts | ✅ Implemented | `MetricsDemoController.java` — writes to `pricing.demo.*` namespace |
| Annual income mandatory (FOIR check) | ✅ Implemented | `PricingRequestDto.java` — `@NotNull` on `annualIncome` |
| Rate validity period configurable | ✅ Implemented | `application.yml` — `epricing.rate-valid-days` (default: 7 days) |

---

## What Was Fixed and Why

### 1. Actuator Endpoint Lockdown

**Before:** The following sensitive endpoints were exposed on port 8081 with no authentication:
- `env` — would expose all Spring config properties including DB password
- `configprops` — would expose all application configuration
- `threaddump` — would expose internal JVM thread state
- `beans`, `conditions` — would expose Spring internal wiring

**After:** Actuator now only exposes `health`, `info`, `prometheus`, `metrics`.

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
```

**Why it matters:** In a bank, anyone with network access to the management port could read your database password via `/actuator/env`. This is a critical information disclosure vulnerability.

---

### 2. Database Credentials — Fail-Fast on Missing Secrets

**Before:**
```yaml
username: ${DB_USERNAME:yugabyte}   # Would silently use 'yugabyte' if env var not set
password: ${DB_PASSWORD:yugabyte}
```

**After:**
```yaml
username: ${DB_USERNAME}   # App refuses to start if not set
password: ${DB_PASSWORD}
```

**Why it matters:** If a developer forgets to set the env vars in a new environment, the application would silently connect using the well-known default YugabyteDB credentials. Fail-fast is always safer than silent fallback for secrets.

---

### 3. CORS — Deny All by Default

**Before:** `CORS_ALLOWED_ORIGINS` defaulted to `*`, meaning any browser origin could call the API.

**After:** If `CORS_ALLOWED_ORIGINS` is not set, no origins are whitelisted. `WebConfig.java` returns early without registering any CORS mapping.

```yaml
cors:
  allowed-origins: ${CORS_ALLOWED_ORIGINS:}   # Empty = deny all cross-origin
```

```java
// WebConfig.java
if (corsAllowedOrigins == null || corsAllowedOrigins.isBlank()) {
    log.warn("CORS_ALLOWED_ORIGINS is not set — all cross-origin requests will be blocked.");
    return;  // Secure default: deny all
}
```

**Production setting:**
```bash
CORS_ALLOWED_ORIGINS=https://grafana.bank.com,https://portal.bank.com
```

---

### 4. Audit Logs — Synchronous (Regulatory Requirement)

**Before:** `recordSuccess()` and `recordRejection()` were both `@Async`. A JVM crash between the pricing response and the async audit thread would silently lose the audit record.

**After:** Both methods are synchronous. They run in the same request thread but use `@Transactional(REQUIRES_NEW)` — an independent transaction that commits even if the caller's transaction rolls back.

```java
// PricingAuditService.java
@Transactional(propagation = Propagation.REQUIRES_NEW)  // No @Async
public void recordSuccess(PricingRequest savedRequest, ...) { ... }

@Transactional(propagation = Propagation.REQUIRES_NEW)  // No @Async
public void recordRejection(PricingRequestDto requestDto, ...) { ... }

@Async("epricingAsyncExecutor")  // Still async — error recovery path only
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void recordTechnicalFailure(...) { ... }
```

**Why it matters:** RBI requires a complete, tamper-proof audit trail of all loan pricing decisions. An async audit that can be lost is a compliance failure.

---

### 5. PII Masking in Logs

**Before:** Customer IDs, loan amounts, credit scores, and annual income appeared in plaintext in all log statements and were shipped to Loki.

**After:** `StructuredLogger.java` masks all PII before writing to logs:

```java
// "CUST001234" → "CUST****1234"
private String maskCustomerId(String customerId) {
    if (customerId == null || customerId.length() <= 4) return "****";
    return customerId.substring(0, 4) + "****" + customerId.substring(customerId.length() - 4);
}

// ₹5,00,000 → "1L-10L"
private String amountBracket(BigDecimal amount) {
    long val = amount.longValue();
    if (val < 100_000)    return "<1L";
    if (val < 1_000_000)  return "1L-10L";
    if (val < 10_000_000) return "10L-1Cr";
    return ">1Cr";
}
```

**Why it matters:** Loki stores logs in plaintext. If Grafana credentials are compromised, all customer financial data would be readable. PII in logs also violates India's DPDP Act (Digital Personal Data Protection Act 2023).

---

### 6. Proxy-Aware Client IP Capture

**Before:** `requestIp` was captured via `httpRequest.getRemoteAddr()`. Behind a load balancer, this always returns the proxy's IP — making the audit log useless for forensics.

**After:** `PricingController.extractClientIp()` checks `X-Forwarded-For` first:

```java
private String extractClientIp(HttpServletRequest request) {
    String xff = request.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isBlank() && !"unknown".equalsIgnoreCase(xff)) {
        return xff.split(",")[0].trim();  // Leftmost = original client
    }
    String xRealIp = request.getHeader("X-Real-IP");
    if (xRealIp != null && !xRealIp.isBlank()) return xRealIp;
    return request.getRemoteAddr();
}
```

---

## Remaining: Authentication (Not Yet Implemented)

The only security control not yet implemented is JWT/OAuth2 authentication.

### What to add:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
```

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://auth.bank.com/realms/banking
```

```java
@PreAuthorize("hasRole('PRICING_USER')")
@PostMapping
public ResponseEntity<PricingResponseDto> calculatePricing(...) { ... }
```

### Rate Limiting (Config-only, not yet wired)

Config exists in `application.yml` but is not connected to any code:
```yaml
epricing:
  rate-limit:
    requests-per-second: 100
    burst-capacity: 200
```
Implementation would use Resilience4j `@RateLimiter` or Spring Cloud Gateway.

---

## Cross-References

- [WebConfig.md](../configuration/WebConfig.md) — CORS configuration
- [COMPLIANCE_FIXES.md](../compliance/COMPLIANCE_FIXES.md) — full record of all compliance changes made
