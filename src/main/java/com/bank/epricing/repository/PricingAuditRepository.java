package com.bank.epricing.repository;

import com.bank.epricing.entity.PricingAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * PricingAuditRepository — Data access for the append-only audit log.
 *
 * NOTE: This repository intentionally does NOT expose delete() or update() methods.
 * Although JpaRepository provides them, we document here that they should NEVER
 * be called on audit logs. In a real banking system, you would:
 *   1. Create a custom base repository that removes delete/update methods
 *   2. Or use database-level permissions (GRANT INSERT, SELECT only — no UPDATE/DELETE)
 *   3. Or use an immutable audit table with DDL trigger preventing updates
 */
@Repository
public interface PricingAuditRepository extends JpaRepository<PricingAuditLog, Long> {

    /**
     * Find all audit events for a specific pricing request.
     * This gives the complete lifecycle of one pricing transaction:
     *   PRICING_REQUESTED → PRICING_CALCULATED → PRICING_APPROVED
     */
    List<PricingAuditLog> findByPricingRequestIdOrderByCreatedAtAsc(Long pricingRequestId);

    /**
     * Find all audit events for a customer — used in fraud investigation.
     * "Show me everything customer CUST001234 did in the last 30 days"
     */
    List<PricingAuditLog> findByCustomerIdAndCreatedAtBetweenOrderByCreatedAtDesc(
        String customerId,
        LocalDateTime from,
        LocalDateTime to
    );

    /**
     * Find all audit events for a specific trace.
     * Links audit logs to distributed traces for complete observability.
     */
    List<PricingAuditLog> findByTraceIdOrderByCreatedAtAsc(String traceId);

    /**
     * Count error events in the last hour — feeds into error rate metric.
     * If error count > threshold → trigger Grafana alert.
     */
    @Query("SELECT COUNT(a) FROM PricingAuditLog a " +
           "WHERE a.outcome = 'FAILURE' AND a.createdAt > :since")
    Long countFailuresSince(@Param("since") LocalDateTime since);

    /**
     * Find slow operations — used in performance dashboards.
     * Any operation taking > 500ms is flagged for investigation.
     */
    @Query("SELECT a FROM PricingAuditLog a " +
           "WHERE a.durationMs > :thresholdMs " +
           "AND a.createdAt > :since " +
           "ORDER BY a.durationMs DESC")
    List<PricingAuditLog> findSlowOperations(
        @Param("thresholdMs") Long thresholdMs,
        @Param("since") LocalDateTime since
    );
}
