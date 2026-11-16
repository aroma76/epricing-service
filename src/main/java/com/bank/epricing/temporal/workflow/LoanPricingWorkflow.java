package com.bank.epricing.temporal.workflow;

import com.bank.epricing.dto.PricingRequestDto;
import com.bank.epricing.temporal.dto.PricingWorkflowResult;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Temporal Workflow Interface for durable Loan Pricing lifecycle.
 * Defines the contract for long-running pricing workflows, signals, and queries.
 */
@WorkflowInterface
public interface LoanPricingWorkflow {

    /**
     * Main workflow orchestration method.
     */
    @WorkflowMethod
    PricingWorkflowResult processLoanPricing(PricingRequestDto requestDto, String requestIp, int rateValidDays);

    /**
     * Signal to approve the locked rate offer.
     */
    @SignalMethod
    void approveQuote();

    /**
     * Signal to reject the locked rate offer.
     */
    @SignalMethod
    void rejectQuote(String reason);

    /**
     * Query current status of the workflow (QUOTED, APPROVED, REJECTED, EXPIRED).
     */
    @QueryMethod
    String getStatus();

    /**
     * Query full workflow snapshot.
     */
    @QueryMethod
    PricingWorkflowResult getWorkflowResult();
}

