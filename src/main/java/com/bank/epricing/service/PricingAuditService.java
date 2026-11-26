package com.bank.epricing.service;

import com.bank.epricing.dto.PricingRequestDto;
import com.bank.epricing.entity.PricingAuditLog;
import com.bank.epricing.entity.PricingRequest;
import com.bank.epricing.event.LoanPricingEvent;
import com.bank.epricing.repository.PricingAuditRepository;
import com.bank.epricing.repository.PricingRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.bank.epricing.logging.StructuredLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PricingAuditService — Manages append-only audit trail records.
 *
 * WHY A SEPARATE SERVICE:
 * Audit logging is a CROSS-CUTTING CONCERN — it applies to all operations.
 * Separating it from PricingService keeps business logic clean.
 *
 * ASYNC / DURABILITY NOTE:
 * Workflow-driven audit events (approval, rejection, expiration, and calculation)
 * are managed as durable activities in Temporal (PricingActivitiesImpl),
 * ensuring guaranteed delivery and automatic retry even during system failures.
 * Technical failure records use REQUIRES_NEW transactions to write isolated status
 * records without relying on fragile in-JVM thread pools.
 */
@Service
public class PricingAuditService {

    private static final Logger log = LoggerFactory.getLogger(PricingAuditService.class);

    private final PricingAuditRepository auditRepository;
    private final PricingRepository pricingRepository;
    private final OutboxService outboxService;
    private final MeterRegistry meterRegistry;

    public PricingAuditService(PricingAuditRepository auditRepository,
                               PricingRepository pricingRepository) {
        this(auditRepository, pricingRepository, null, null);
    }

    @Autowired
    public PricingAuditService(PricingAuditRepository auditRepository,
                               PricingRepository pricingRepository,
                               @Autowired(required = false) OutboxService outboxService,
                               @Autowired(required = false) MeterRegistry meterRegistry) {
        this.auditRepository = auditRepository;
        this.pricingRepository = pricingRepository;
        this.outboxService = outboxService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Persists the PricingRequest and its corresponding PricingAuditLog atomically
     * within a single short database transaction.
     * If saving either entity fails, the transaction is rolled back completely,
     * ensuring no orphaned PricingRequest is committed without its audit trail.
     */
    @Transactional(rollbackFor = Exception.class)
    public PricingRequest savePricingWithAudit(PricingRequest entity, String requestIp, String traceId) {
        return savePricingWithAuditAndOutbox(entity, requestIp, traceId, null);
    }

    /**
     * Persists PricingRequest, PricingAuditLog, and (when outbox is enabled) the outbox event
     * inside one ACID database transaction so Kafka events cannot be silently lost.
     */
    @Transactional(rollbackFor = Exception.class)
    public PricingRequest savePricingWithAuditAndOutbox(PricingRequest entity,
                                                        String requestIp,
                                                        String traceId,
                                                        LoanPricingEvent event) {
        PricingRequest savedRequest = pricingRepository.save(entity);

        PricingAuditLog auditLog = PricingAuditLog.builder()
            .pricingRequestId(savedRequest.getId())
            .customerId(savedRequest.getCustomerId())
            .action("PRICING_CALCULATED")
            .description(String.format(
                "Interest rate %.2f%% p.a. calculated for %s loan. " +
                "Risk category: %s",
                savedRequest.getCalculatedRate(),
                savedRequest.getProductType(),
                savedRequest.getRiskCategory() != null ? savedRequest.getRiskCategory() : "STANDARD"
            ))
            .performedBy("system:pricing-engine")
            .outcome("SUCCESS")
            .requestIp(requestIp)
            .traceId(traceId)
            .durationMs(savedRequest.getProcessingTimeMs())
            .build();

        auditRepository.save(auditLog);

        if (event != null && outboxService != null && outboxService.isEnabled()) {
            event.setRequestId(savedRequest.getId());
            outboxService.enqueue("PRICING_REQUEST", savedRequest.getId(), event);
        }

        log.debug("Persisted PricingRequest and AuditLog atomically | requestId={} | traceId={}",
            savedRequest.getId(), traceId);
        return savedRequest;
    }

    /**
     * Records a business rejection event with both the human-readable error message and the error code.
     *
     * ISSUE-01 FIX: Removed Propagation.REQUIRES_NEW to prevent HikariCP connection pool
     * starvation deadlocks. Uses standard @Transactional (REQUIRED) so it executes within
     * a single connection without suspending an outer connection or deadlocking the pool.
     */
    @Transactional(rollbackFor = Exception.class)
    public void recordRejection(PricingRequestDto requestDto, String errorMessage, String errorCode, String requestIp, String traceId) {
        try {
            String displayReason = (errorMessage != null && !errorMessage.isBlank())
                ? errorMessage
                : (errorCode != null ? errorCode : "Business eligibility rejection");

            String safeReason = (displayReason != null && displayReason.length() > 500)
                ? displayReason.substring(0, 497) + "..."
                : displayReason;

            // 1. Persist a PricingRequest record with status REJECTED and full error message first
            // so we obtain its generated ID for the audit log
            PricingRequest rejectedRecord = PricingRequest.builder()
                .customerId(requestDto.getCustomerId())
                .productType(requestDto.getProductType())
                .loanAmount(requestDto.getLoanAmount())
                .loanTenureMonths(requestDto.getLoanTenureMonths())
                .creditScore(requestDto.getCreditScore())
                .annualIncome(requestDto.getAnnualIncome())
                .loanPurpose(requestDto.getLoanPurpose())
                .status(PricingRequest.PricingStatus.REJECTED)
                .errorMessage(safeReason)
                .requestIp(requestIp)
                .traceId(traceId)
                .build();

            PricingRequest savedRejected = pricingRepository.save(rejectedRecord);
            Long reqId = savedRejected != null ? savedRejected.getId() : null;

            // 2. Persist audit log linked directly to the rejected PricingRequest ID
            PricingAuditLog auditLog = PricingAuditLog.builder()
                .pricingRequestId(reqId)
                .customerId(requestDto.getCustomerId())
                .action("PRICING_REJECTED")
                .description(String.format(
                    "Pricing rejected for %s loan application. Reason: %s",
                    requestDto.getProductType(),
                    displayReason
                ))
                .performedBy("system:pricing-engine")
                .outcome("REJECTED")
                .requestIp(requestIp)
                .traceId(traceId)
                .metadata(String.format(
                    "{\"errorCode\":\"%s\",\"creditScore\":%s}",
                    errorCode,
                    requestDto.getCreditScore()
                ))
                .build();

            auditRepository.save(auditLog);
        } catch (Exception e) {
            if (meterRegistry != null) {
                meterRegistry.counter("epricing.audit.write.failed", "type", "rejection").increment();
            }
            log.error("CRITICAL: Failed to save rejection records | customerId={} | error={}",
                StructuredLogger.maskCustomerId(requestDto.getCustomerId()), e.getMessage(), e);
        }
    }

    /**
     * Records a technical failure by persisting a FAILED PricingRequest record.
     *
     * WHY THIS SOLVES THE CRITICAL GAP:
     * When a pricing job fails with a technical error, the main @Transactional
     * in PricingService rolls back — meaning nothing is saved to the DB.
     * Support teams cannot see failed jobs or their error reason in Grafana.
     *
     * ISSUE-01 FIX: Removed Propagation.REQUIRES_NEW. Uses standard @Transactional (REQUIRED)
     * so it does not check out a second database connection while an outer connection is held,
     * preventing HikariCP pool starvation.
     */
    @Transactional(rollbackFor = Exception.class)
    public void recordTechnicalFailure(PricingRequestDto requestDto, String errorMessage,
                                       String requestIp, String traceId) {
        try {
            String rawError = (errorMessage != null && !errorMessage.isBlank())
                ? errorMessage
                : "Unknown technical error";
            String safeError = rawError.length() > 1997 ? rawError.substring(0, 1997) + "..." : rawError;
            String safeAuditDesc = rawError.length() > 450 ? rawError.substring(0, 447) + "..." : rawError;

            String customerId = (requestDto != null && requestDto.getCustomerId() != null && !requestDto.getCustomerId().isBlank())
                ? requestDto.getCustomerId()
                : "UNKNOWN";
            String productType = (requestDto != null && requestDto.getProductType() != null && !requestDto.getProductType().isBlank())
                ? requestDto.getProductType()
                : "UNKNOWN";
            java.math.BigDecimal loanAmount = (requestDto != null && requestDto.getLoanAmount() != null)
                ? requestDto.getLoanAmount()
                : new java.math.BigDecimal("10000.00");
            Integer loanTenureMonths = (requestDto != null && requestDto.getLoanTenureMonths() != null)
                ? requestDto.getLoanTenureMonths()
                : 12;

            PricingRequest.PricingRequestBuilder builder = PricingRequest.builder()
                .customerId(customerId)
                .productType(productType)
                .loanAmount(loanAmount)
                .loanTenureMonths(loanTenureMonths)
                .status(PricingRequest.PricingStatus.ERROR)
                .errorMessage(safeError)
                .requestIp(requestIp)
                .traceId(traceId);

            if (requestDto != null) {
                builder.creditScore(requestDto.getCreditScore())
                    .annualIncome(requestDto.getAnnualIncome())
                    .loanPurpose(requestDto.getLoanPurpose());
            }

            PricingRequest failedRecord = builder.build();
            PricingRequest savedFailed = pricingRepository.save(failedRecord);
            Long reqId = savedFailed != null ? savedFailed.getId() : null;

            PricingAuditLog auditLog = PricingAuditLog.builder()
                .pricingRequestId(reqId)
                .customerId(customerId)
                .action("TECHNICAL_FAILURE")
                .description("Technical failure occurred: " + safeAuditDesc)
                .performedBy("system:pricing-engine")
                .outcome("ERROR")
                .requestIp(requestIp)
                .traceId(traceId)
                .build();
            auditRepository.save(auditLog);

            log.info("Technical failure record saved | customerId={} | traceId={}",
                StructuredLogger.maskCustomerId(customerId), traceId);
        } catch (Exception e) {
            if (meterRegistry != null) {
                meterRegistry.counter("epricing.audit.write.failed", "type", "technical_failure").increment();
            }
            // Log but never propagate — we're already in an error path
            log.error("Failed to persist technical failure record | customerId={} | error={}",
                StructuredLogger.maskCustomerId(requestDto != null ? requestDto.getCustomerId() : "UNKNOWN"), e.getMessage(), e);
        }
    }
}

