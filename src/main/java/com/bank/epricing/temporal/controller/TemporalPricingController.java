package com.bank.epricing.temporal.controller;

import com.bank.epricing.dto.PricingRequestDto;
import com.bank.epricing.temporal.dto.PricingWorkflowResult;
import com.bank.epricing.temporal.service.TemporalPricingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST Controller exposing endpoints for Temporal durable Loan Pricing workflows.
 */
@RestController
@RequestMapping("/workflow/pricing")
public class TemporalPricingController {

    private static final Logger log = LoggerFactory.getLogger(TemporalPricingController.class);

    private final TemporalPricingService temporalPricingService;

    public TemporalPricingController(TemporalPricingService temporalPricingService) {
        this.temporalPricingService = temporalPricingService;
    }

    /**
     * Start a durable loan pricing workflow.
     */
    @PostMapping("/start")
    public ResponseEntity<PricingWorkflowResult> startWorkflow(
            @Valid @RequestBody PricingRequestDto requestDto,
            HttpServletRequest request) {

        String clientIp = request.getHeader("X-Forwarded-For");
        if (clientIp == null || clientIp.isBlank()) {
            clientIp = request.getRemoteAddr();
        }

        log.info("Received request to start Temporal workflow | customerId={}", requestDto.getCustomerId());
        PricingWorkflowResult result = temporalPricingService.startPricingWorkflow(requestDto, clientIp);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    /**
     * Signal approval for a rate quote.
     */
    @PostMapping("/{workflowId}/approve")
    public ResponseEntity<Map<String, String>> approveWorkflow(
            @PathVariable("workflowId") String workflowId) {

        log.info("Signaling approval for workflowId={}", workflowId);
        temporalPricingService.approveQuote(workflowId);

        return ResponseEntity.ok(Map.of(
            "workflow_id", workflowId,
            "status", "APPROVED",
            "message", "Approval signal dispatched to workflow"
        ));
    }

    /**
     * Signal rejection for a rate quote.
     */
    @PostMapping("/{workflowId}/reject")
    public ResponseEntity<Map<String, String>> rejectWorkflow(
            @PathVariable("workflowId") String workflowId,
            @RequestBody(required = false) Map<String, String> body) {

        String reason = body != null ? body.get("reason") : "Customer rejected offer";
        log.info("Signaling rejection for workflowId={} | reason={}", workflowId, reason);
        temporalPricingService.rejectQuote(workflowId, reason);

        return ResponseEntity.ok(Map.of(
            "workflow_id", workflowId,
            "status", "REJECTED",
            "reason", reason,
            "message", "Rejection signal dispatched to workflow"
        ));
    }

    /**
     * Query status and snapshot of a workflow.
     */
    @GetMapping("/{workflowId}")
    public ResponseEntity<PricingWorkflowResult> getWorkflowStatus(
            @PathVariable("workflowId") String workflowId) {

        PricingWorkflowResult result = temporalPricingService.getWorkflowResult(workflowId);
        return ResponseEntity.ok(result);
    }
}
