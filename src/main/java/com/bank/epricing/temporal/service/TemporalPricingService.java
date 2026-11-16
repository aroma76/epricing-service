package com.bank.epricing.temporal.service;

import com.bank.epricing.dto.PricingRequestDto;
import com.bank.epricing.exception.PricingException;
import com.bank.epricing.temporal.dto.PricingWorkflowResult;
import com.bank.epricing.temporal.workflow.LoanPricingWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Service to manage Temporal Loan Pricing Workflows.
 * Provides APIs for starting workflows, sending signals, and querying status.
 */
@Service
public class TemporalPricingService {

    private static final Logger log = LoggerFactory.getLogger(TemporalPricingService.class);

    private final Optional<WorkflowClient> workflowClient;

    @Value("${epricing.temporal.enabled:false}")
    private boolean temporalEnabled;

    @Value("${epricing.temporal.task-queue:epricing-pricing-queue}")
    private String taskQueue;

    @Value("${epricing.rate-valid-days:7}")
    private int rateValidDays;

    @Autowired
    public TemporalPricingService(Optional<WorkflowClient> workflowClient) {
        this.workflowClient = workflowClient;
    }

    public boolean isTemporalEnabled() {
        return temporalEnabled && workflowClient.isPresent();
    }

    /**
     * Starts a new durable Loan Pricing Workflow.
     */
    public PricingWorkflowResult startPricingWorkflow(PricingRequestDto requestDto, String requestIp) {
        ensureTemporalAvailable();

        String workflowId = "pricing-" + requestDto.getCustomerId() + "-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("Launching Temporal LoanPricingWorkflow | workflowId={} | customerId={}",
            workflowId, requestDto.getCustomerId());

        WorkflowClient client = workflowClient.get();
        LoanPricingWorkflow workflow = client.newWorkflowStub(
            LoanPricingWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(taskQueue)
                .build()
        );

        // Start workflow execution asynchronously
        WorkflowClient.start(workflow::processLoanPricing, requestDto, requestIp, rateValidDays);

        // Fetch initial snapshot after activity execution begins
        return new PricingWorkflowResult(
            workflowId,
            requestDto.getCustomerId(),
            requestDto.getProductType(),
            "PROCESSING",
            null
        );
    }

    /**
     * Approves an active quote via Temporal signal.
     */
    public void approveQuote(String workflowId) {
        ensureTemporalAvailable();
        log.info("Sending approve signal to workflow | workflowId={}", workflowId);
        LoanPricingWorkflow workflow = workflowClient.get().newWorkflowStub(LoanPricingWorkflow.class, workflowId);
        workflow.approveQuote();
    }

    /**
     * Rejects an active quote via Temporal signal.
     */
    public void rejectQuote(String workflowId, String reason) {
        ensureTemporalAvailable();
        log.info("Sending reject signal to workflow | workflowId={} | reason={}", workflowId, reason);
        LoanPricingWorkflow workflow = workflowClient.get().newWorkflowStub(LoanPricingWorkflow.class, workflowId);
        workflow.rejectQuote(reason != null ? reason : "Customer opted out");
    }

    /**
     * Queries the status of an active or completed workflow.
     */
    public String getWorkflowStatus(String workflowId) {
        ensureTemporalAvailable();
        try {
            LoanPricingWorkflow workflow = workflowClient.get().newWorkflowStub(LoanPricingWorkflow.class, workflowId);
            return workflow.getStatus();
        } catch (Exception e) {
            log.warn("Workflow status query failed for workflowId={}: {}", workflowId, e.getMessage());
            return "PROCESSING";
        }
    }

    /**
     * Queries the full workflow result snapshot.
     */
    public PricingWorkflowResult getWorkflowResult(String workflowId) {
        ensureTemporalAvailable();
        try {
            LoanPricingWorkflow workflow = workflowClient.get().newWorkflowStub(LoanPricingWorkflow.class, workflowId);
            PricingWorkflowResult result = workflow.getWorkflowResult();
            return result != null ? result : new PricingWorkflowResult(workflowId, null, null, "PROCESSING", null);
        } catch (Exception e) {
            log.warn("Workflow result query failed for workflowId={}: {}", workflowId, e.getMessage());
            return new PricingWorkflowResult(workflowId, null, null, "PROCESSING", null);
        }
    }

    private void ensureTemporalAvailable() {
        if (!isTemporalEnabled()) {
            throw new PricingException(
                "Temporal workflow orchestration is disabled. Set epricing.temporal.enabled=true to enable.",
                "TEMPORAL_DISABLED",
                HttpStatus.BAD_REQUEST
            );
        }
    }
}
