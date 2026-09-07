# PricingRepository

**Package:** `com.bank.epricing.repository`
**File:** [`PricingRepository.java`](../../src/main/java/com/bank/epricing/repository/PricingRepository.java)
**Stereotype:** `@Repository`, extends `JpaRepository<PricingRequest, Long>`

---

## Purpose

`PricingRepository` is the **data access layer** for `PricingRequest` entities. By extending `JpaRepository`, Spring Data JPA generates a runtime proxy that implements all CRUD operations and custom query methods automatically — no SQL boilerplate required.

---

## Inherited CRUD Methods (from JpaRepository)

These are available without any additional code:

| Method | SQL Equivalent |
|---|---|
| `save(entity)` | `INSERT` or `UPDATE` |
| `findById(id)` | `SELECT * WHERE id = ?` |
| `findAll()` | `SELECT * FROM pricing_requests` |
| `deleteById(id)` | `DELETE WHERE id = ?` |
| `count()` | `SELECT COUNT(*)` |
| `existsById(id)` | `SELECT 1 WHERE id = ?` |

---

## Custom Query Methods

### Derived Query Methods

Spring Data JPA parses method names to generate SQL automatically.

#### `findByCustomerId(String customerId) → List<PricingRequest>`

```
SELECT * FROM pricing_requests WHERE customer_id = :customerId
```

Used by: `PricingService.getPricingHistory(customerId)` — returns all pricing records for a customer.

---

#### `findTop10ByOrderByCreatedAtDesc() → List<PricingRequest>`

```
SELECT * FROM pricing_requests ORDER BY created_at DESC LIMIT 10
```

Used when no `customerId` is provided — returns the 10 most-recent records across all customers.

---

#### `findByCustomerIdAndStatus(String customerId, PricingStatus status) → List<PricingRequest>`

```
SELECT * FROM pricing_requests WHERE customer_id = ? AND status = ?
```

Business use: "Show PENDING requests for customer CUST001234" — pipeline monitoring.

---

#### `findByProductTypeAndStatus(String productType, PricingStatus status) → List<PricingRequest>`

```
SELECT * FROM pricing_requests WHERE product_type = ? AND status = ?
```

Business use: "How many HOME_LOANs are in CALCULATED status?" — Grafana pipeline dashboard.

---

#### `findByTraceId(String traceId) → Optional<PricingRequest>`

```
SELECT * FROM pricing_requests WHERE trace_id = :traceId LIMIT 1
```

**Critical for incident debugging.** When a customer provides their traceId from a failed request, you can find the exact database record and cross-reference with the distributed trace in Grafana Tempo.

---

### Custom JPQL Queries (`@Query`)

JPQL queries reference Java entity class names and field names, not SQL table/column names.

#### `countByCreatedAtBetween(LocalDateTime, LocalDateTime) → Long`

```jpql
SELECT COUNT(pr) FROM PricingRequest pr
WHERE pr.createdAt BETWEEN :startTime AND :endTime
```

Used to power a custom Micrometer Gauge: `pricing.requests.hourly.count`. Enables Grafana alert: "If fewer than 100 pricing requests in the last hour → alert."

---

#### `countByStatus(PricingStatus status) → Long`

```jpql
SELECT COUNT(pr) FROM PricingRequest pr WHERE pr.status = :status
```

Pipeline monitoring: "How many requests are stuck in PENDING state?" High PENDING count indicates processing backlog.

---

#### `findAverageProcessingTimeSince(LocalDateTime since) → Optional<Double>`

```jpql
SELECT AVG(pr.processingTimeMs) FROM PricingRequest pr
WHERE pr.createdAt > :since AND pr.status = 'CALCULATED'
```

Business SLO metric: average processing time should be < 200ms. Returns `Optional<Double>` because `AVG()` returns `NULL` when there are no matching rows.

---

#### `sumLoanAmountBetween(LocalDateTime, LocalDateTime) → Optional<BigDecimal>`

```jpql
SELECT SUM(pr.loanAmount) FROM PricingRequest pr
WHERE pr.createdAt BETWEEN :startTime AND :endTime
AND pr.status IN ('CALCULATED', 'APPROVED')
```

Revenue metric: total approved/calculated loan volume for a time period. Feeds Grafana business metrics dashboard.

---

### Database-Agnostic JPQL Aggregation

#### `countByProductTypeSince(LocalDateTime since) → List<Object[]>`

```java
@Query("SELECT pr.productType, COUNT(pr) FROM PricingRequest pr " +
       "WHERE pr.createdAt > :since " +
       "GROUP BY pr.productType")
List<Object[]> countByProductTypeSince(@Param("since") LocalDateTime since);
```

**Why JPQL (Database-Agnostic) instead of Native SQL:**
Previously, native SQL with PostgreSQL-specific `INTERVAL '24' HOUR` syntax was used, which broke on H2 in local development and integration test profiles. By converting to standard JPQL and passing `since` (`LocalDateTime.now().minusHours(24)`), the query is **fully portable across H2, PostgreSQL, and YugabyteDB**.

Returns `List<Object[]>` where each row contains `[String productType, Long count]`, used to populate breakdown panels on the operations dashboard.

---

## OTel Instrumentation

Spring Data JPA + Hibernate automatically creates **database spans** in OTel traces. Each `save()` or `findBy*()` call appears as a child span in Grafana Tempo, showing:
- SQL query text
- Execution duration
- Database connection pool status

HikariCP connection pool metrics are automatically exposed via Micrometer as:
- `hikaricp_connections_active{pool="epricing-pool"}`
- `hikaricp_connections_idle{pool="epricing-pool"}`
- `hikaricp_connections_pending{pool="epricing-pool"}`

---

## Cross-References

- [PricingRequest.md](../entities/PricingRequest.md) — entity definition
- [PricingService.md](../services/PricingService.md) — business calls to this repository
- [DatabaseDesign.md](../database/DatabaseDesign.md) — schema and ERD
- [ApplicationConfig.md](../configuration/ApplicationConfig.md) — HikariCP pool configuration
