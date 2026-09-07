package com.bank.epricing.service;

import com.bank.epricing.dto.PricingRequestDto;
import com.bank.epricing.entity.PricingAuditLog;
import com.bank.epricing.entity.PricingRequest;
import com.bank.epricing.repository.PricingAuditRepository;
import com.bank.epricing.repository.PricingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
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
 * WHY @Async FOR AUDIT:
 * Audit log writing should NOT slow down the pricing calculation response.
 * By making audit writes asynchronous, the client gets their pricing response
 * immediately, while the audit log is written in the background.
 *
 * TRADE-OFF: If the app crashes between pricing calculation and audit log write,
 * the audit record may be lost. For REGULATORY audit logs, you might choose
 * synchronous writing with a separate database for isolation.
 * For OPERATIONAL audit logs (what we have here), async is acceptable.
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
     * Records a successful pricing calculation.
     *
     * @Transactional(propagation = REQUIRES_NEW):
     * Creates a NEW transaction, separate from the caller's transaction.
     *
     * WHY: If PricingService's transaction rolls back (some error after pricing),
     * we still want the audit record to be saved. The audit of "what was attempted"
     * is valuable even when the attempt failed.
     *
     * Without REQUIRES_NEW: If PricingService rolls back → audit also rolls back
     * With REQUIRES_NEW: Audit has its own transaction → survives caller rollback
     */
    @Async("epricingAsyncExecutor")  // Runs on the async thread pool defined in WebConfig
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(PricingRequest savedRequest, String requestIp, String traceId) {
        try {
            PricingAuditLog auditLog = PricingAuditLog.builder()
                .pricingRequestId(savedRequest.getId())
                .customerId(savedRequest.getCustomerId())
                .action("PRICING_CALCULATED")
                .description(String.format(
                    "Interest rate %.2f%% p.a. calculated for %s loan of ₹%s. " +
                    "EMI: ₹%s/month. Risk: %s",
                    savedRequest.getCalculatedRate(),
                    savedRequest.getProductType(),
                    savedRequest.getLoanAmount().toPlainString(),
                    savedRequest.getEmiAmount() != null ? savedRequest.getEmiAmount().toPlainString() : "N/A",
                    "CALCULATED"
                ))
                .performedBy("system:pricing-engine")
                .outcome("SUCCESS")
                .requestIp(requestIp)
                .traceId(traceId)
                .durationMs(savedRequest.getProcessingTimeMs())
                .build();

            auditRepository.save(auditLog);
            log.debug("Audit record saved | pricingRequestId={} | traceId={}", savedRequest.getId(), traceId);
        } catch (Exception e) {
            // NEVER let audit log failure propagate to the caller.
            // The business operation succeeded — don't fail it because of audit.
            // In production, this would trigger an alert to fix the audit service.
            log.error("Failed to save audit log | pricingRequestId={} | error={}",
                savedRequest.getId(), e.getMessage(), e);
        }
    }

    /**
     * Records a business rejection event with both the human-readable error message and the error code.
     */
    @Async("epricingAsyncExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRejection(PricingRequestDto requestDto, String errorMessage, String errorCode, String requestIp, String traceId) {
        try {
            String displayReason = (errorMessage != null && !errorMessage.isBlank())
                ? errorMessage
                : (errorCode != null ? errorCode : "Business eligibility rejection");

            PricingAuditLog auditLog = PricingAuditLog.builder()
                .customerId(requestDto.getCustomerId())
                .action("PRICING_REJECTED")
                .description(String.format(
                    "Pricing rejected for %s %s loan of ₹%s. Reason: %s",
                    requestDto.getCustomerId(),
                    requestDto.getProductType(),
                    requestDto.getLoanAmount().toPlainString(),
                    displayReason
                ))
                .performedBy("system:pricing-engine")
                .outcome("REJECTED")
                .requestIp(requestIp)
                .traceId(traceId)
                .metadata(String.format(
                    "{\"errorCode\":\"%s\",\"creditScore\":%s,\"loanAmount\":\"%s\"}",
                    errorCode,
                    requestDto.getCreditScore(),
                    requestDto.getLoanAmount().toPlainString()
                ))
                .build();

            auditRepository.save(auditLog);

            // Persist a PricingRequest record with status REJECTED and full error message
            // so Grafana's L1 Jobs table reflects business rejections live from DB
            PricingRequest rejectedRecord = PricingRequest.builder()
                .customerId(requestDto.getCustomerId())
                .productType(requestDto.getProductType())
                .loanAmount(requestDto.getLoanAmount())
                .loanTenureMonths(requestDto.getLoanTenureMonths())
                .creditScore(requestDto.getCreditScore())
                .annualIncome(requestDto.getAnnualIncome())
                .loanPurpose(requestDto.getLoanPurpose())
                .status(PricingRequest.PricingStatus.REJECTED)
                .errorMessage(displayReason)
                .requestIp(requestIp)
                .traceId(traceId)
                .build();

            pricingRepository.save(rejectedRecord);
        } catch (Exception e) {
            log.error("Failed to save rejection records | customerId={} | error={}",
                requestDto.getCustomerId(), e.getMessage(), e);
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
     *
     * @Async: Fire-and-forget on the background thread pool.
     * The pricing response (or error) is returned to the client immediately.
     */
    @Async("epricingAsyncExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordTechnicalFailure(PricingRequestDto requestDto, String errorMessage,
                                       String requestIp, String traceId) {
        try {
            String safeError = (errorMessage != null && !errorMessage.isBlank())
                ? errorMessage
                : "Unknown technical error";
            PricingRequest.PricingRequestBuilder builder = PricingRequest.builder()
                .status(PricingRequest.PricingStatus.ERROR)
                .errorMessage(safeError)
                .requestIp(requestIp)
                .traceId(traceId);

            if (requestDto != null) {
                builder.customerId(requestDto.getCustomerId())
                    .productType(requestDto.getProductType())
                    .loanAmount(requestDto.getLoanAmount())
                    .loanTenureMonths(requestDto.getLoanTenureMonths())
                    .creditScore(requestDto.getCreditScore())
                    .annualIncome(requestDto.getAnnualIncome())
                    .loanPurpose(requestDto.getLoanPurpose());
            }

            PricingRequest failedRecord = builder.build();
            pricingRepository.save(failedRecord);
            log.info("Technical failure record saved | customerId={} | traceId={}",
                requestDto != null ? requestDto.getCustomerId() : "N/A", traceId);
        } catch (Exception e) {
            // Log but never propagate — we're already in an error path
            log.error("Failed to persist technical failure record | customerId={} | error={}",
                requestDto.getCustomerId(), e.getMessage(), e);
        }
    }
}

