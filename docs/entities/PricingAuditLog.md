# PricingAuditLog Entity

**Package:** `com.bank.epricing.entity`
**File:** [`PricingAuditLog.java`](../../src/main/java/com/bank/epricing/entity/PricingAuditLog.java)
**JPA Table:** `pricing_audit_logs`

---

## Purpose

`PricingAuditLog` is the **immutable audit trail entity**. Every significant business event (pricing calculated, rejected, approved) produces one or more audit log entries. This table is:

- **Append-only** — records are never updated or deleted in normal operation.
- **Linked to traces** — every record carries the OTel `traceId` for cross-signal correlation.
- **Compliance-driven** — banking regulations require a complete audit trail of all pricing decisions.

---

## Fields

| Field | Column | Type | Description |
|---|---|---|---|
| `id` | `id` | `Long` | Primary key (auto-generated) |
| `pricingRequestId` | `pricing_request_id` | `Long` | FK reference to `pricing_requests.id` |
| `customerId` | `customer_id` | `String(50)` | Customer identifier (denormalized for query efficiency) |
| `action` | `action` | `String(100)` | Event type (see Action Types below) |
| `description` | `description` | `TEXT` | Human-readable event description |
| `performedBy` | `performed_by` | `String(100)` | Actor — `system:pricing-engine` or user identity |
| `outcome` | `outcome` | `String(20)` | `SUCCESS`, `FAILURE`, `REJECTED` |
| `metadata` | `metadata` | `TEXT` | JSON blob of additional context for the event |
| `requestIp` | `request_ip` | `String(50)` | Client IP address |
| `userAgent` | `user_agent` | `String(500)` | HTTP User-Agent header from the request |
| `traceId` | `trace_id` | `String(64)` | OTel trace ID for log–trace correlation |
| `durationMs` | `duration_ms` | `Long` | Operation duration in milliseconds |
| `createdAt` | `created_at` | `LocalDateTime` | Record creation timestamp (`@CreationTimestamp`) |

---

## Action Types

The `action` field is a free-form String. Actions used in the codebase:

| Action | Trigger |
|---|---|
| `PRICING_CALCULATED` | Successful pricing calculation |
| `PRICING_REJECTED` | Business rule rejection (low credit score, etc.) |
| `PRICING_APPROVED` | Manual or automated approval by risk team |
| `PRICING_ERROR` | Technical error during processing |

---

## Why Denormalized `customerId`?

The `customerId` field is duplicated from `pricing_requests` in `pricing_audit_logs`. This violates first normal form but is a deliberate design choice:

- **Query efficiency**: `findByCustomerIdAndCreatedAtBetween()` avoids a JOIN to `pricing_requests`.
- **Audit immutability**: If the customer record is ever deleted from upstream systems, the audit log remains self-contained.
- **Regulatory compliance**: Regulators require that audit logs be readable even after source records are purged.

---

## Immutability Enforcement

The `PricingAuditRepository` intentionally inherits `delete` and `update` methods from `JpaRepository` but documents them as **forbidden**:

```java
/**
 * NOTE: This repository intentionally does NOT expose delete() or update() methods.
 * They should NEVER be called on audit logs. In production:
 *   1. Create a custom base repository removing delete/update
 *   2. Use database-level permissions (GRANT INSERT, SELECT only)
 *   3. Use DDL triggers preventing updates
 */
```

> **Status:** The current implementation inherits `deleteById` etc. from `JpaRepository`. Production hardening (custom base repository or DB-level constraints) is **not yet implemented**.

---

## Seed Data

Four audit log entries are pre-loaded at startup from `V4__seed_initial_data.sql`:

```sql
INSERT INTO pricing_audit_logs
    (pricing_request_id, customer_id, action, outcome, trace_id, duration_ms, ...)
VALUES
    (1, 'CUST001234', 'PRICING_CALCULATED', 'SUCCESS', 'abc123def456', 142, ...),
    (7, 'CUST006789', 'PRICING_REJECTED',  'REJECTED', 'fgh678ijk901', 78,  ...);
```

---

## Compliance Notes

In a production banking system, audit logs require:

- **Retention**: Minimum 7 years per RBI guidelines.
- **Encryption**: Sensitive fields (customer ID, IP) may need encryption at rest.
- **Access control**: Separate read-only role for compliance/audit teams.
- **Archival**: After 30 days, logs should be shipped to long-term storage (S3/GCS) before local deletion.

---

## Cross-References

- [PricingAuditService.md](../services/PricingAuditService.md) — how audit logs are written
- [PricingAuditRepository.md](../repositories/PricingAuditRepository.md) — queries on this entity
- [AuditTrail.md](../features/AuditTrail.md) — full audit feature documentation
- [DatabaseDesign.md](../database/DatabaseDesign.md) — schema and ERD
