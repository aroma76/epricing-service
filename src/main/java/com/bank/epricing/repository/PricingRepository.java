package com.bank.epricing.repository;

import com.bank.epricing.entity.PricingRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  PricingRepository.java — Spring Data JPA Repository                    ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║  WHY THIS INTERFACE EXISTS:                                              ║
 * ║  This interface is the DATA ACCESS LAYER for pricing requests.          ║
 * ║                                                                          ║
 * ║  By extending JpaRepository<PricingRequest, Long>, Spring Data JPA      ║
 * ║  AUTOMATICALLY GENERATES IMPLEMENTATIONS at runtime for:                ║
 * ║    - save(entity)          → INSERT or UPDATE                           ║
 * ║    - findById(id)          → SELECT WHERE id = ?                        ║
 * ║    - findAll()             → SELECT * FROM pricing_requests             ║
 * ║    - delete(entity)        → DELETE WHERE id = ?                        ║
 * ║    - count()               → SELECT COUNT(*) FROM pricing_requests      ║
 * ║    - existsById(id)        → SELECT 1 WHERE id = ?                      ║
 * ║                                                                          ║
 * ║  You write ZERO SQL for these operations. Spring generates them.        ║
 * ║                                                                          ║
 * ║  HOW SPRING DATA JPA WORKS:                                             ║
 * ║  At startup, Spring scans interfaces extending JpaRepository.           ║
 * ║  It creates a dynamic proxy (a generated class at runtime) that        ║
 * ║  implements each method by translating it to a SQL query.              ║
 * ║                                                                          ║
 * ║  OBSERVABILITY VALUE:                                                    ║
 * ║  HikariCP metrics (connection pool) are automatically collected        ║
 * ║  by Micrometer and exposed on /actuator/prometheus. The queries here   ║
 * ║  generate database spans in OpenTelemetry traces.                       ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */
@Repository
// JpaRepository<EntityType, PrimaryKeyType>
// This gives us all standard CRUD methods for free.
public interface PricingRepository extends JpaRepository<PricingRequest, Long> {

    /**
     * DERIVED QUERY: findByCustomerId
     *
     * Spring Data JPA reads method names and generates SQL automatically.
     * "findBy" + "CustomerId" → SELECT * FROM pricing_requests WHERE customer_id = ?
     *
     * The method name IS the query. No SQL written. No @Query needed.
     * This is called a "Derived Query Method" — Spring parses the name.
     *
     * Runtime: Spring translates this to:
     *   SELECT pr.* FROM pricing_requests pr WHERE pr.customer_id = :customerId
     */
    List<PricingRequest> findByCustomerId(String customerId);

    /**
     * PAGINATED QUERY: findByCustomerId with Pageable
     * Enables scalable, chunked retrieval of customer pricing history.
     */
    Page<PricingRequest> findByCustomerId(String customerId, Pageable pageable);

    /**
     * DERIVED QUERY: findTop10ByOrderByCreatedAtDesc
     * Retrieves the 10 most recent pricing requests.
     * Used for general pricing history when no customerId is specified.
     */
    List<PricingRequest> findTop10ByOrderByCreatedAtDesc();

    /**
     * DERIVED QUERY: findByCustomerIdAndStatus
     * Multiple conditions joined by "And" in the method name.
     * → SELECT * WHERE customer_id = ? AND status = ?
     *
     * @param customerId The customer to query for
     * @param status     The PricingStatus enum value to filter by
     */
    List<PricingRequest> findByCustomerIdAndStatus(
        String customerId,
        PricingRequest.PricingStatus status
    );

    /**
     * DERIVED QUERY: findByProductTypeAndStatus
     * Used for business metrics: "How many HOME_LOANs are in CALCULATED status?"
     * This powers Grafana dashboards showing pipeline depth by product type.
     */
    List<PricingRequest> findByProductTypeAndStatus(
        String productType,
        PricingRequest.PricingStatus status
    );

    /**
     * CUSTOM JPQL QUERY using @Query annotation.
     *
     * WHY @Query instead of derived method?
     * Derived method would be:
     *   findByCreatedAtBetween(LocalDateTime start, LocalDateTime end)
     * But we want COUNT — derived methods cannot easily express aggregation.
     *
     * @Query uses JPQL (Java Persistence Query Language) — queries Java entities,
     * not database tables. "PricingRequest pr" references the Java class,
     * not the table "pricing_requests".
     *
     * WHY THIS QUERY:
     * This is used by our custom Micrometer Gauge metric:
     *   pricing.requests.hourly.count → feeds into Grafana dashboards
     *   Allows alerting: "If fewer than 100 pricing requests in last hour → ALERT"
     */
    @Query("SELECT COUNT(pr) FROM PricingRequest pr " +
           "WHERE pr.createdAt BETWEEN :startTime AND :endTime")
    Long countByCreatedAtBetween(
        @Param("startTime") LocalDateTime startTime,
        @Param("endTime") LocalDateTime endTime
    );

    /**
     * Counts requests by status — used for pipeline monitoring.
     * → "How many requests are stuck in PENDING state?" (should be near zero)
     * Feeds the Grafana panel: "Pricing Request Status Distribution"
     */
    @Query("SELECT COUNT(pr) FROM PricingRequest pr WHERE pr.status = :status")
    Long countByStatus(@Param("status") PricingRequest.PricingStatus status);

    /**
     * Average processing time — used for custom Micrometer Gauge.
     * This is a business SLO metric: average processing should be < 200ms.
     * If this creeps up, it indicates database/service degradation.
     *
     * Returns Optional<Double> because if there are no records, AVG returns null.
     * Always handle Optional to avoid NullPointerException.
     */
    @Query("SELECT AVG(pr.processingTimeMs) FROM PricingRequest pr " +
           "WHERE pr.createdAt > :since AND pr.status = com.bank.epricing.entity.PricingRequest.PricingStatus.CALCULATED")
    Optional<Double> findAverageProcessingTimeSince(@Param("since") LocalDateTime since);

    /**
     * Find by trace ID — CRITICAL FOR DEBUGGING.
     * When a customer reports a failed request with their traceId,
     * you can find the exact database record and cross-reference with
     * the distributed trace in Grafana Tempo.
     *
     * Returns Optional<PricingRequest> because a traceId may not exist
     * (defensive programming — never assume data exists).
     */
    Optional<PricingRequest> findByTraceId(String traceId);

    /**
     * Revenue calculation — total loan value requested in a time period.
     * Used in Grafana business metrics dashboard.
     * Returns null if no records exist in the time range.
     */
    @Query("SELECT SUM(pr.loanAmount) FROM PricingRequest pr " +
           "WHERE pr.createdAt BETWEEN :startTime AND :endTime " +
           "AND pr.status IN (com.bank.epricing.entity.PricingRequest.PricingStatus.CALCULATED, com.bank.epricing.entity.PricingRequest.PricingStatus.APPROVED)")
    Optional<BigDecimal> sumLoanAmountBetween(
        @Param("startTime") LocalDateTime startTime,
        @Param("endTime") LocalDateTime endTime
    );

    /**
     * Count pricing requests grouped by product type since a given timestamp.
     *
     * WHY JPQL (not native SQL):
     * The original native query used PostgreSQL-specific INTERVAL syntax
     * which breaks on H2 (used for local development and tests).
     * JPQL is database-agnostic — works on H2, PostgreSQL, and YugabyteDB.
     *
     * Usage: pass LocalDateTime.now().minusHours(24) as the 'since' parameter.
     *
     * @param since Lower bound timestamp to count from
     */
    @Query("SELECT pr.productType, COUNT(pr) FROM PricingRequest pr " +
           "WHERE pr.createdAt > :since " +
           "GROUP BY pr.productType")
    List<Object[]> countByProductTypeSince(@Param("since") LocalDateTime since);
}
