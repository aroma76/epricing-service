package com.bank.epricing.service;

import com.bank.epricing.dto.PricingRequestDto;
import com.bank.epricing.entity.PricingAuditLog;
import com.bank.epricing.entity.PricingRequest;
import com.bank.epricing.repository.PricingAuditRepository;
import com.bank.epricing.repository.PricingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.bank.epricing.logging.StructuredLogger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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

    public PricingAuditService(PricingAuditRepository auditRepository,
                               PricingRepository pricingRepository) {
        this.auditRepository = auditRepository;
        this.pricingRepository = pricingRepository;
    }

    /**
     * Persists the PricingRequest and its corresponding PricingAuditLog atomically
     * within a single short database transaction.
     * If saving either entity fails, the transaction is rolled back completely,
     * ensuring no orphaned PricingRequest is committed without its audit trail.
     */
    @Transactional(rollbackFor = Exception.class)
    public PricingRequest savePricingWithAudit(PricingRequest entity, String requestIp, String traceId) {
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
        log.debug("Persisted PricingRequest and AuditLog atomically | requestId={} | traceId={}",
            savedRequest.getId(), traceId);
        return savedRequest;
    }

    /**
     * Records a business rejection event with both the human-readable error message and the error code.
     *
     * COMPLIANCE: This method is SYNCHRONOUS (no @Async).
     * Business rejections are regulatory records (FOIR rejection, credit score rejection).
     * They must be committed in the same request cycle, not fire-and-forget.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
            log.error("Failed to save rejection records | customerId={} | error={}",
                StructuredLogger.maskCustomerId(requestDto.getCustomerId()), e.getMessage(), e);
        }
    }

    /**
     * Backward-compatible overload for recording rejections with errorCode only.
     */
    public void recordRejection(PricingRequestDto requestDto, String errorCode, String requestIp, String traceId) {
        recordRejection(requestDto, null, errorCode, requestIp, traceId);
    }

    /**
     * Records a technical failure by persisting a FAILED PricingRequest record.
     *
     * WHY THIS SOLVES THE CRITICAL GAP:
     * When a pricing job fails with a technical error, the main @Transactional
     * in PricingService rolls back — meaning nothing is saved to the DB.
     * Support teams cannot see failed jobs or their error reason in Grafana.
     *
     * THIS METHOD runs in a NEW transaction (REQUIRES_NEW) separate from the
     * failing transaction. Even though PricingService's transaction rolls back,
     * this transaction commits independently — saving the failed record with
     * status=ERROR and the exact error_message into pricing_requests.
     *
     * Grafana's L1 Jobs Table queries pricing_requests directly and displays
     * the error_message column — so support teams see what went wrong without
     * logging into YugabyteDB.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordTechnicalFailure(PricingRequestDto requestDto, String errorMessage,
                                       String requestIp, String traceId) {
        try {
            String safeError = (errorMessage != null && !errorMessage.isBlank())
                ? errorMessage
                : "Unknown technical error";
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
                .description("Technical failure occurred: " + safeError)
                .performedBy("system:pricing-engine")
                .outcome("ERROR")
                .requestIp(requestIp)
                .traceId(traceId)
                .build();
            auditRepository.save(auditLog);

            log.info("Technical failure record saved | customerId={} | traceId={}",
                StructuredLogger.maskCustomerId(customerId), traceId);
        } catch (Exception e) {
            // Log but never propagate — we're already in an error path
            log.error("Failed to persist technical failure record | customerId={} | error={}",
                StructuredLogger.maskCustomerId(requestDto != null ? requestDto.getCustomerId() : "UNKNOWN"), e.getMessage(), e);
        }
    }
}

