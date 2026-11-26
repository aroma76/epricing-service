package com.bank.epricing.temporal.service;

import com.bank.epricing.dto.PricingRequestDto;
import com.bank.epricing.exception.PricingException;
import com.bank.epricing.logging.StructuredLogger;
import com.bank.epricing.temporal.dto.PricingWorkflowResult;
import com.bank.epricing.temporal.workflow.LoanPricingWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
        return startPricingWorkflow(requestDto, requestIp, requestDto != null ? requestDto.getIdempotencyKey() : null);
    }

    public PricingWorkflowResult startPricingWorkflow(PricingRequestDto requestDto, String requestIp,
            String idempotencyKey) {
        ensureTemporalAvailable();

        String effectiveKey = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? idempotencyKey.trim()
                : (requestDto != null ? requestDto.getIdempotencyKey() : null);

        // Hash the customer ID so it is not stored in plaintext in Temporal workflowId
        String hashedCustomerId = hashCustomerId(requestDto.getCustomerId());
        String maskedCustomerId = StructuredLogger.maskCustomerId(requestDto.getCustomerId());
        String suffix = (effectiveKey != null && !effectiveKey.isBlank())
                ? hashShort(effectiveKey, 16)
                : UUID.randomUUID().toString().substring(0, 8);
        String workflowId = "pricing-" + hashedCustomerId + "-" + suffix;

        log.info("Launching Temporal LoanPricingWorkflow | workflowId={} | customerId={} | idempotent={}",
                workflowId, maskedCustomerId, effectiveKey != null && !effectiveKey.isBlank());

        WorkflowClient client = workflowClient.get();
        WorkflowOptions.Builder optionsBuilder = WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(taskQueue);
        if (effectiveKey != null && !effectiveKey.isBlank()) {
            optionsBuilder.setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE);
        }

        LoanPricingWorkflow workflow = client.newWorkflowStub(LoanPricingWorkflow.class, optionsBuilder.build());

        PricingRequestDto payloadDto = requestDto;
        if (effectiveKey != null && !effectiveKey.isBlank()
                && !effectiveKey.equals(requestDto.getIdempotencyKey())) {
            payloadDto = PricingRequestDto.builder()
                    .customerId(requestDto.getCustomerId())
                    .productType(requestDto.getProductType())
                    .loanAmount(requestDto.getLoanAmount())
                    .loanTenureMonths(requestDto.getLoanTenureMonths())
                    .creditScore(requestDto.getCreditScore())
                    .annualIncome(requestDto.getAnnualIncome())
                    .loanPurpose(requestDto.getLoanPurpose())
                    .idempotencyKey(effectiveKey)
                    .build();
        }

        try {
            WorkflowClient.start(workflow::processLoanPricing, payloadDto, requestIp, rateValidDays);
        } catch (WorkflowExecutionAlreadyStarted alreadyStarted) {
            log.info("Idempotent Temporal workflow start returned existing execution | workflowId={}", workflowId);
            return getWorkflowResult(workflowId);
        } catch (WorkflowServiceException ex) {
            throw new PricingException.PricingServiceUnavailableException(
                    "Unable to start Temporal workflow: " + ex.getMessage());
        }

        return new PricingWorkflowResult(
                workflowId,
                requestDto.getCustomerId(),
                requestDto.getProductType(),
                "PROCESSING",
                null);
    }

    /**
     * Approves an active quote via Temporal signal.
     */
    public void approveQuote(String workflowId) {
        ensureTemporalAvailable();
        log.info("Sending approve signal to workflow | workflowId={}", workflowId);
        try {
            LoanPricingWorkflow workflow = workflowClient.get().newWorkflowStub(LoanPricingWorkflow.class, workflowId);
            workflow.approveQuote();
        } catch (WorkflowNotFoundException notFound) {
            throw new PricingException.WorkflowNotFoundException(workflowId);
        } catch (WorkflowServiceException ex) {
            throw new PricingException.PricingServiceUnavailableException(
                    "Unable to dispatch approval signal: " + ex.getMessage());
        }
    }

    /**
     * Rejects an active quote via Temporal signal.
     */
    public void rejectQuote(String workflowId, String reason) {
        ensureTemporalAvailable();
        String effectiveReason = (reason != null && !reason.isBlank()) ? reason.trim() : "Customer opted out";
        log.info("Sending reject signal to workflow | workflowId={} | reason={}", workflowId, effectiveReason);
        try {
            LoanPricingWorkflow workflow = workflowClient.get().newWorkflowStub(LoanPricingWorkflow.class, workflowId);
            workflow.rejectQuote(effectiveReason);
        } catch (WorkflowNotFoundException notFound) {
            throw new PricingException.WorkflowNotFoundException(workflowId);
        } catch (WorkflowServiceException ex) {
            throw new PricingException.PricingServiceUnavailableException(
                    "Unable to dispatch rejection signal: " + ex.getMessage());
        }
    }

    /**
     * Queries the status of an active or completed workflow.
     */
    public String getWorkflowStatus(String workflowId) {
        ensureTemporalAvailable();
        try {
            LoanPricingWorkflow workflow = workflowClient.get().newWorkflowStub(LoanPricingWorkflow.class, workflowId);
            return workflow.getStatus();
        } catch (WorkflowNotFoundException notFound) {
            throw new PricingException.WorkflowNotFoundException(workflowId);
        } catch (Exception e) {
            log.warn("Workflow status query failed for workflowId={}: {}", workflowId, e.getMessage());
            Optional<String> terminalStatus = resolveTerminalExecutionStatus(workflowId);
            if (terminalStatus.isPresent()) {
                return "FAILED";
            }
            if (e instanceof WorkflowServiceException) {
                throw new PricingException.PricingServiceUnavailableException("Temporal workflow query unavailable");
            }
            return "FAILED";
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
        } catch (WorkflowNotFoundException notFound) {
            throw new PricingException.WorkflowNotFoundException(workflowId);
        } catch (Exception e) {
            log.warn("Workflow result query failed for workflowId={}: {}", workflowId, e.getMessage());
            Optional<String> terminalStatus = resolveTerminalExecutionStatus(workflowId);
            if (terminalStatus.isPresent()) {
                PricingWorkflowResult failedResult = new PricingWorkflowResult(workflowId, null, null, "FAILED", null);
                failedResult.setRejectionReason("Workflow execution ended with status: " + terminalStatus.get());
                return failedResult;
            }
            if (e instanceof WorkflowServiceException) {
                throw new PricingException.PricingServiceUnavailableException("Temporal workflow result unavailable");
            }
            PricingWorkflowResult failedResult = new PricingWorkflowResult(workflowId, null, null, "FAILED", null);
            failedResult.setRejectionReason("Workflow query failed: " + e.getMessage());
            return failedResult;
        }
    }

    private Optional<String> resolveTerminalExecutionStatus(String workflowId) {
        try {
            io.temporal.client.WorkflowExecutionDescription desc = workflowClient.get()
                    .newUntypedWorkflowStub(workflowId)
                    .describe();
            io.temporal.api.enums.v1.WorkflowExecutionStatus execStatus = desc.getStatus();
            if (execStatus == io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED
                    || execStatus == io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED
                    || execStatus == io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TIMED_OUT
                    || execStatus == io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CANCELED) {
                return Optional.of(execStatus.name());
            }
        } catch (WorkflowNotFoundException notFound) {
            throw new PricingException.WorkflowNotFoundException(workflowId);
        } catch (Exception descEx) {
            log.debug("Could not describe workflow execution for {}: {}", workflowId, descEx.getMessage());
            if (descEx instanceof io.grpc.StatusRuntimeException grpcEx
                    && grpcEx.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                throw new PricingException.WorkflowNotFoundException(workflowId);
            }
        }
        return Optional.empty();
    }

    private void ensureTemporalAvailable() {
        if (!isTemporalEnabled()) {
            throw new PricingException(
                    "Temporal workflow orchestration is disabled. Set epricing.temporal.enabled=true to enable.",
                    "TEMPORAL_DISABLED",
                    HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * ISSUE-05 FIX: Returns the first 12 hex characters of SHA-256(customerId).
     * Short enough to serve as a stable correlator, but not reversible.
     */
    private String hashCustomerId(String customerId) {
        if (customerId == null || customerId.isBlank())
            return "unknown";
        return hashShort(customerId, 12);
    }

    private String hashShort(String input, int hexLength) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, Math.min(hexLength, sb.length()));
        } catch (NoSuchAlgorithmException e) {
            return "hash-err";
        }
    }
}
