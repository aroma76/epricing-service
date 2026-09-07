package com.bank.epricing.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * PricingAuditLog — Immutable Audit Trail Entity for regulatory compliance.
 */
@Entity
@Table(
    name = "pricing_audit_logs",
    indexes = {
        @Index(name = "idx_audit_pricing_request_id", columnList = "pricing_request_id"),
        @Index(name = "idx_audit_customer_id", columnList = "customer_id"),
        @Index(name = "idx_audit_action", columnList = "action"),
        @Index(name = "idx_audit_created_at", columnList = "created_at"),
        @Index(name = "idx_audit_trace_id", columnList = "trace_id")
    }
)
public class PricingAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pricing_request_id", updatable = false)
    private Long pricingRequestId;

    @Column(name = "customer_id", length = 50, updatable = false)
    private String customerId;

    @Column(name = "action", nullable = false, length = 100, updatable = false)
    private String action;

    @Column(name = "description", columnDefinition = "TEXT", updatable = false)
    private String description;

    @Column(name = "performed_by", length = 100, updatable = false)
    private String performedBy;

    @Column(name = "outcome", length = 20, updatable = false)
    private String outcome;

    @Column(name = "metadata", columnDefinition = "TEXT", updatable = false)
    private String metadata;

    @Column(name = "request_ip", length = 50, updatable = false)
    private String requestIp;

    @Column(name = "user_agent", length = 500, updatable = false)
    private String userAgent;

    @Column(name = "trace_id", length = 64, updatable = false)
    private String traceId;

    @Column(name = "duration_ms", updatable = false)
    private Long durationMs;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public PricingAuditLog() {
    }

    public PricingAuditLog(Long id, Long pricingRequestId, String customerId, String action, String description,
                           String performedBy, String outcome, String metadata, String requestIp,
                           String userAgent, String traceId, Long durationMs, LocalDateTime createdAt) {
        this.id = id;
        this.pricingRequestId = pricingRequestId;
        this.customerId = customerId;
        this.action = action;
        this.description = description;
        this.performedBy = performedBy;
        this.outcome = outcome;
        this.metadata = metadata;
        this.requestIp = requestIp;
        this.userAgent = userAgent;
        this.traceId = traceId;
        this.durationMs = durationMs;
        this.createdAt = createdAt;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getPricingRequestId() { return pricingRequestId; }
    public void setPricingRequestId(Long pricingRequestId) { this.pricingRequestId = pricingRequestId; }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getPerformedBy() { return performedBy; }
    public void setPerformedBy(String performedBy) { this.performedBy = performedBy; }

    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }

    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }

    public String getRequestIp() { return requestIp; }
    public void setRequestIp(String requestIp) { this.requestIp = requestIp; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public static PricingAuditLogBuilder builder() {
        return new PricingAuditLogBuilder();
    }

    public static class PricingAuditLogBuilder {
        private Long id;
        private Long pricingRequestId;
        private String customerId;
        private String action;
        private String description;
        private String performedBy;
        private String outcome;
        private String metadata;
        private String requestIp;
        private String userAgent;
        private String traceId;
        private Long durationMs;
        private LocalDateTime createdAt;

        PricingAuditLogBuilder() {}

        public PricingAuditLogBuilder id(Long id) { this.id = id; return this; }
        public PricingAuditLogBuilder pricingRequestId(Long pricingRequestId) { this.pricingRequestId = pricingRequestId; return this; }
        public PricingAuditLogBuilder customerId(String customerId) { this.customerId = customerId; return this; }
        public PricingAuditLogBuilder action(String action) { this.action = action; return this; }
        public PricingAuditLogBuilder description(String description) { this.description = description; return this; }
        public PricingAuditLogBuilder performedBy(String performedBy) { this.performedBy = performedBy; return this; }
        public PricingAuditLogBuilder outcome(String outcome) { this.outcome = outcome; return this; }
        public PricingAuditLogBuilder metadata(String metadata) { this.metadata = metadata; return this; }
        public PricingAuditLogBuilder requestIp(String requestIp) { this.requestIp = requestIp; return this; }
        public PricingAuditLogBuilder userAgent(String userAgent) { this.userAgent = userAgent; return this; }
        public PricingAuditLogBuilder traceId(String traceId) { this.traceId = traceId; return this; }
        public PricingAuditLogBuilder durationMs(Long durationMs) { this.durationMs = durationMs; return this; }
        public PricingAuditLogBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }

        public PricingAuditLog build() {
            return new PricingAuditLog(id, pricingRequestId, customerId, action, description, performedBy,
                    outcome, metadata, requestIp, userAgent, traceId, durationMs, createdAt);
        }
    }
}
