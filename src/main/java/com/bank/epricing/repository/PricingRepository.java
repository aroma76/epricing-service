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
     * Find by trace ID — CRITICAL FOR DEBUGGING.
     * When a customer reports a failed request with their traceId,
     * you can find the exact database record and cross-reference with
     * the distributed trace in Grafana Tempo.
     */
    Optional<PricingRequest> findByTraceId(String traceId);
}

