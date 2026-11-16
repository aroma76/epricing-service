package com.bank.epricing.temporal.workflow;

import com.bank.epricing.dto.PricingRequestDto;
import com.bank.epricing.dto.PricingResponseDto;
import com.bank.epricing.temporal.activity.PricingActivities;
import com.bank.epricing.temporal.dto.PricingWorkflowResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;

/**
 * Deterministic Temporal Workflow Implementation for Loan Pricing.
 * Handles calculation, rate-lock duration, signals (approval/rejection), and expiration.
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
                    .build()
            )
            .build()
    );

    @Override
    public PricingWorkflowResult processLoanPricing(PricingRequestDto requestDto, String requestIp, int rateValidDays) {
        String workflowId = Workflow.getInfo().getWorkflowId();
        log.info("Starting LoanPricingWorkflow | workflowId={} | customerId={}",
            workflowId, requestDto.getCustomerId());

        // Initialize snapshot immediately so early queries do not return null
        result = new PricingWorkflowResult(
            workflowId,
            requestDto.getCustomerId(),
            requestDto.getProductType(),
            status,
            null
        );

        // Step 1: Calculate and persist pricing quote
        PricingResponseDto quote = activities.calculateAndPersistPricing(requestDto, requestIp);
        status = "QUOTED";
        result.setStatus(status);
        result.setQuote(quote);

        String quoteId = quote.getRequestId() != null
            ? quote.getRequestId().toString()
            : null;

        // Step 2: Durable wait for approval/rejection or rate-lock expiration
        int days = rateValidDays > 0 ? rateValidDays : 7;
        log.info("Rate lock active for {} days. Awaiting customer signal | workflowId={}", days, workflowId);

        boolean decisionMade = Workflow.await(Duration.ofDays(days), () -> approved || rejected);

        if (approved) {
            status = "APPROVED";
            result.setStatus(status);
            activities.recordQuoteApproval(quoteId, requestDto.getCustomerId());
            log.info("Pricing offer approved by customer | workflowId={}", workflowId);
        } else if (rejected) {
            status = "REJECTED";
            result.setStatus(status);
            result.setRejectionReason(rejectionReason);
            activities.recordQuoteRejection(quoteId, requestDto.getCustomerId(), rejectionReason);
            log.info("Pricing offer rejected by customer | workflowId={} | reason={}", workflowId, rejectionReason);
        } else {
            status = "EXPIRED";
            result.setStatus(status);
            activities.recordQuoteExpiration(quoteId, requestDto.getCustomerId());
            log.info("Pricing offer expired after {} days | workflowId={}", days, workflowId);
        }

        return result;
    }

    @Override
    public void approveQuote() {
        if (!"QUOTED".equals(status)) {
            log.warn("Cannot approve quote in state: {}", status);
            return;
        }
        this.approved = true;
    }

    @Override
    public void rejectQuote(String reason) {
        if (!"QUOTED".equals(status)) {
            log.warn("Cannot reject quote in state: {}", status);
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
