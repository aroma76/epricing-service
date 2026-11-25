package com.bank.epricing.temporal.workflow;

import com.bank.epricing.dto.PricingRequestDto;
import com.bank.epricing.dto.PricingResponseDto;
import com.bank.epricing.logging.StructuredLogger;
import com.bank.epricing.temporal.activity.PricingActivities;
import com.bank.epricing.temporal.dto.PricingWorkflowResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;

/**
 * Deterministic Temporal Workflow Implementation for Loan Pricing.
 * Handles calculation, rate-lock duration, signals (approval/rejection), and
 * expiration.
 * Follows strict Temporal determinism guidelines (zero non-deterministic APIs).
 */
public class LoanPricingWorkflowImpl implements LoanPricingWorkflow {

    private static final Logger log = Workflow.getLogger(LoanPricingWorkflowImpl.class);

    private String status = "INITIALIZING";
    private boolean approved = false;
    private boolean rejected = false;
    private String rejectionReason = null;
    private PricingWorkflowResult result;

    // Configure resilient activity options with automatic exponential backoff retry
    private final PricingActivities activities = Workflow.newActivityStub(
            PricingActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(
                            RetryOptions.newBuilder()
                                    .setInitialInterval(Duration.ofSeconds(1))
                                    .setBackoffCoefficient(2.0)
                                    .setMaximumInterval(Duration.ofSeconds(10))
                                    .setMaximumAttempts(5)
                                    .setDoNotRetry(
                                            com.bank.epricing.exception.PricingException.class.getName(),
                                            com.bank.epricing.exception.PricingException.InsufficientCreditScoreException.class
                                                    .getName(),
                                            com.bank.epricing.exception.PricingException.LoanAmountExceedsEligibilityException.class
                                                    .getName(),
                                            com.bank.epricing.exception.PricingException.FoirExceededException.class
                                                    .getName(),
                                            com.bank.epricing.exception.PricingException.UnsupportedProductTypeException.class
                                                    .getName(),
                                            // ISSUE-06 FIX: PricingCalculationException must be in doNotRetry;
                                            // Temporal does NOT respect Java inheritance for setDoNotRetry matching.
                                            // Without this entry, any calculation failure causes 5x retry storms
                                            // and 5x duplicate error records in pricing_requests.
                                            com.bank.epricing.exception.PricingException.PricingCalculationException.class
                                                    .getName())
                                    .build())
                    .build());

    @Override
    public PricingWorkflowResult processLoanPricing(PricingRequestDto requestDto, String requestIp, int rateValidDays) {
        String workflowId = Workflow.getInfo().getWorkflowId();
        log.info("Starting LoanPricingWorkflow | workflowId={} | customerId={}",
            workflowId, StructuredLogger.maskCustomerId(requestDto.getCustomerId()));

        // Initialize snapshot immediately so early queries do not return null
        result = new PricingWorkflowResult(
                workflowId,
                requestDto.getCustomerId(),
                requestDto.getProductType(),
                status,
                null);

        // Step 1: Calculate and persist pricing quote
        PricingResponseDto quote;
        try {
            quote = activities.calculateAndPersistPricing(requestDto, requestIp);
            status = "QUOTED";
            result.setStatus(status);
            result.setQuote(quote);
        } catch (Exception e) {
            log.warn("Pricing calculation failed/rejected in workflow | workflowId={} | error={}",
                    workflowId, e.getMessage());
            status = "REJECTED";
            result.setStatus(status);
            String failureReason = (e.getCause() != null && e.getCause().getMessage() != null && !e.getCause().getMessage().isBlank())
                ? e.getCause().getMessage()
                : (e.getMessage() != null ? e.getMessage() : "Pricing calculation rejected");
            result.setRejectionReason(failureReason);
            result.setUpdatedAt(java.time.Instant.ofEpochMilli(Workflow.currentTimeMillis()).toString());
            return result;
        }

        String quoteId = quote.getRequestId() != null
                ? quote.getRequestId().toString()
                : null;

        // Step 2: Durable wait for approval/rejection or rate-lock expiration (unless already signaled)
        int days = rateValidDays > 0 ? rateValidDays : 7;
        log.info("Rate lock active for {} days. Awaiting customer signal | workflowId={}", days, workflowId);

        if (!approved && !rejected) {
            Workflow.await(Duration.ofDays(days), () -> approved || rejected);
        }

        String decisionTime = java.time.Instant.ofEpochMilli(Workflow.currentTimeMillis()).toString();
        if (approved) {
            status = "APPROVED";
            result.setStatus(status);
            result.setUpdatedAt(decisionTime);
            activities.recordQuoteApproval(quoteId, requestDto.getCustomerId());
            log.info("Pricing offer approved by customer | workflowId={}", workflowId);
        } else if (rejected) {
            status = "REJECTED";
            result.setStatus(status);
            result.setRejectionReason(rejectionReason);
            result.setUpdatedAt(decisionTime);
            activities.recordQuoteRejection(quoteId, requestDto.getCustomerId(), rejectionReason);
            log.info("Pricing offer rejected by customer | workflowId={} | reason={}", workflowId, rejectionReason);
        } else {
            status = "EXPIRED";
            result.setStatus(status);
            result.setUpdatedAt(decisionTime);
            activities.recordQuoteExpiration(quoteId, requestDto.getCustomerId());
            log.info("Pricing offer expired after {} days | workflowId={}", days, workflowId);
        }

        return result;
    }

    @Override
    public void approveQuote() {
        if (approved || rejected || "EXPIRED".equals(status)) {
            log.warn("Cannot approve quote in state: {} (approved={}, rejected={})", status, approved, rejected);
            return;
        }
        this.approved = true;
    }

    @Override
    public void rejectQuote(String reason) {
        if (approved || rejected || "EXPIRED".equals(status)) {
            log.warn("Cannot reject quote in state: {} (approved={}, rejected={})", status, approved, rejected);
            return;
        }
        this.rejected = true;
        this.rejectionReason = reason;
    }

    @Override
    public String getStatus() {
        return status;
    }

    @Override
    public PricingWorkflowResult getWorkflowResult() {
        return result;
    }
}
