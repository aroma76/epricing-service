# PricingAuditRepository

**Package:** `com.bank.epricing.repository`
**File:** [`PricingAuditRepository.java`](../../src/main/java/com/bank/epricing/repository/PricingAuditRepository.java)
**Stereotype:** `@Repository`, extends `JpaRepository<PricingAuditLog, Long>`

---

## Purpose

`PricingAuditRepository` is the **data access layer for the audit trail**. It provides queries optimised for audit-specific use cases: tracing a request's complete lifecycle, customer fraud investigation, and performance monitoring.

---

## Design Intent: Append-Only

The repository interface documents that `delete()` and `update()` methods inherited from `JpaRepository` **must never be called** on audit logs:

```java
/**
 * NOTE: This repository intentionally does NOT expose delete() or update() methods.
 * ...In production, you would:
 *   1. Create a custom base repository that removes delete/update methods
 *   2. Use database-level permissions (GRANT INSERT, SELECT only — no UPDATE/DELETE)
 *   3. Use an immutable audit table with DDL trigger preventing updates
 */
```

> **Status:** Append-only enforcement via a custom base repository or database constraints is **not yet implemented**.

---

## Query Methods

### Derived Query Methods

#### `findByPricingRequestIdOrderByCreatedAtAsc(Long pricingRequestId) → List<PricingAuditLog>`

```
SELECT * FROM pricing_audit_logs
WHERE pricing_request_id = ?
ORDER BY created_at ASC
```

Returns the full audit trail for a single pricing transaction in chronological order. Used to inspect the complete lifecycle:
```
PRICING_REQUESTED → PRICING_CALCULATED → PRICING_APPROVED
```

---

#### `findByCustomerIdAndCreatedAtBetweenOrderByCreatedAtDesc(String, LocalDateTime, LocalDateTime) → List<PricingAuditLog>`

```
SELECT * FROM pricing_audit_logs
WHERE customer_id = ? AND created_at BETWEEN ? AND ?
ORDER BY created_at DESC
```

Used in **fraud investigation**: "Show me everything customer CUST001234 did in the last 30 days." Returns newest events first.

Note: `customerId` is denormalized into `pricing_audit_logs` (not just in `pricing_requests`) specifically to make this query efficient without a JOIN.

---

#### `findByTraceIdOrderByCreatedAtAsc(String traceId) → List<PricingAuditLog>`

```
SELECT * FROM pricing_audit_logs
WHERE trace_id = ?
ORDER BY created_at ASC
```

Links audit logs to distributed traces. A Grafana Tempo trace shows spans; you can cross-reference with audit logs by the same `traceId`. Provides end-to-end visibility: infrastructure trace + business audit record in one view.

---

### JPQL Queries

#### `countFailuresSince(LocalDateTime since) → Long`

```jpql
SELECT COUNT(a) FROM PricingAuditLog a
WHERE a.outcome = 'FAILURE' AND a.createdAt > :since
```

Error rate metric. Used to feed a custom Micrometer Gauge:
- `errorCount > threshold` → trigger Grafana alert.
- Complements the HTTP server error rate metric with a business-level view: "How many pricing operations failed in the last hour?"

---

#### `findSlowOperations(Long thresholdMs, LocalDateTime since) → List<PricingAuditLog>`

```jpql
SELECT a FROM PricingAuditLog a
WHERE a.durationMs > :thresholdMs AND a.createdAt > :since
ORDER BY a.durationMs DESC
```

Performance investigation tool. Finds audit records for operations that exceeded a latency threshold (default: 500ms). Returns slowest operations first.

Used in: Grafana "Slow Request" panel — table of recent slow operations with full audit context.

---

## Cross-References

- [PricingAuditLog.md](../entities/PricingAuditLog.md) — entity definition
- [PricingAuditService.md](../services/PricingAuditService.md) — writes to this repository
- [AuditTrail.md](../features/AuditTrail.md) — full audit feature
- [DatabaseDesign.md](../database/DatabaseDesign.md) — schema details
