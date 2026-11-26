package com.bank.epricing.temporal.activity;

import com.bank.epricing.dto.PricingRequestDto;
import com.bank.epricing.dto.PricingResponseDto;
import com.bank.epricing.entity.PricingAuditLog;
import com.bank.epricing.entity.PricingRequest;
import com.bank.epricing.event.LoanPricingEvent;
import com.bank.epricing.logging.StructuredLogger;
import com.bank.epricing.repository.PricingAuditRepository;
import com.bank.epricing.repository.PricingRepository;
import com.bank.epricing.service.OutboxService;
import com.bank.epricing.service.PricingEventProducer;
import com.bank.epricing.service.PricingService;
import io.temporal.activity.Activity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Implementation of Temporal Pricing Activities.
 * Activities bridge Temporal workflow executions with Spring Boot business
 * services,
 * YugabyteDB transactional persistence, and Apache Kafka domain event
 * streaming.
 *
 * ISSUE-01 FIX: Removed class-level @Transactional to eliminate outer
 * transaction spanning
 * calculateAndPersistPricing, which caused HikariCP connection pool starvation.
 * Discrete update
 * methods manage their own short-lived transactions.
 *
 * ISSUE-02 FIX: Kafka event publishing in publishStatusEvent is deferred to
 * afterCommit()
 * when an active database transaction is present, preventing dual-write hazards
 * and connection pinning.
 *
 * ISSUE-03 FIX: Added async whenComplete callback to publishPricingEvent to
 * ensure failures are logged.
 *
 * ISSUE-07 FIX: Handles OptimisticLockingFailureException gracefully on
 * concurrent state transitions.
 *
 * ISSUE-08 FIX: Generates UTC Instant timestamps for domain events.
 */
@Component
public class PricingActivitiesImpl implements PricingActivities {

    private static final Logger log = LoggerFactory.getLogger(PricingActivitiesImpl.class);

    private static final Set<PricingRequest.PricingStatus> TERMINAL_STATUSES = EnumSet.of(
            PricingRequest.PricingStatus.APPROVED,
            PricingRequest.PricingStatus.REJECTED,
            PricingRequest.PricingStatus.EXPIRED);

    private final PricingService pricingService;
    private final PricingAuditRepository auditRepository;
    private final PricingRepository pricingRepository;
    private final PricingEventProducer pricingEventProducer;
    private final OutboxService outboxService;

    @Autowired
    public PricingActivitiesImpl(PricingService pricingService,
            PricingAuditRepository auditRepository,
            PricingRepository pricingRepository,
            @Autowired(required = false) PricingEventProducer pricingEventProducer,
            @Autowired(required = false) OutboxService outboxService) {
        this.pricingService = pricingService;
        this.auditRepository = auditRepository;
        this.pricingRepository = pricingRepository;
        this.pricingEventProducer = pricingEventProducer;
        this.outboxService = outboxService;
    }

    public PricingActivitiesImpl(PricingService pricingService,
            PricingAuditRepository auditRepository,
            PricingRepository pricingRepository,
            PricingEventProducer pricingEventProducer) {
        this(pricingService, auditRepository, pricingRepository, pricingEventProducer, null);
    }

    public PricingActivitiesImpl(PricingService pricingService,
            PricingAuditRepository auditRepository,
            PricingRepository pricingRepository) {
        this(pricingService, auditRepository, pricingRepository, null, null);
    }

    @Override
    public PricingResponseDto calculateAndPersistPricing(PricingRequestDto requestDto, String requestIp) {
        String workflowId = resolveCurrentWorkflowId();
        String idempotencyKey = (requestDto.getIdempotencyKey() != null && !requestDto.getIdempotencyKey().isBlank())
                ? requestDto.getIdempotencyKey().trim()
                : (workflowId != null ? "temporal:" + workflowId : null);
        log.info("Executing Temporal Activity: calculateAndPersistPricing | workflowId={} | customerId={}",
                workflowId, StructuredLogger.maskCustomerId(requestDto.getCustomerId()));
        return pricingService.calculatePricing(
                requestDto,
                requestIp != null ? requestIp : "127.0.0.1",
                idempotencyKey,
                workflowId);
    }

    private String resolveCurrentWorkflowId() {
        try {
            return Activity.getExecutionContext().getInfo().getWorkflowId();
        } catch (RuntimeException ignored) {
            // Direct Spring/unit test invocation without Temporal ActivityExecutionContext
            return null;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordQuoteApproval(String quoteId, String customerId) {
        log.info("Executing Temporal Activity: recordQuoteApproval | quoteId={} | customerId={}",
                quoteId, StructuredLogger.maskCustomerId(customerId));
        transitionQuoteStatus(
                quoteId,
                customerId,
                PricingRequest.PricingStatus.APPROVED,
                "RATE_APPROVED",
                "approve",
                "approval",
                "Customer accepted locked interest rate offer",
                null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordQuoteRejection(String quoteId, String customerId, String reason) {
        log.info("Executing Temporal Activity: recordQuoteRejection | quoteId={} | customerId={} | reason={}",
                quoteId, StructuredLogger.maskCustomerId(customerId), reason);
        transitionQuoteStatus(
                quoteId,
                customerId,
                PricingRequest.PricingStatus.REJECTED,
                "RATE_REJECTED",
                "reject",
                "rejection",
                "Quote rejected: " + reason,
                reason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordQuoteExpiration(String quoteId, String customerId) {
        log.info("Executing Temporal Activity: recordQuoteExpiration | quoteId={} | customerId={}",
                quoteId, StructuredLogger.maskCustomerId(customerId));
        transitionQuoteStatus(
                quoteId,
                customerId,
                PricingRequest.PricingStatus.EXPIRED,
                "RATE_EXPIRED",
                "expire",
                "expiration",
                "Quote expired after validity window elapsed",
                null);
    }

    private void transitionQuoteStatus(String quoteId,
                                       String customerId,
                                       PricingRequest.PricingStatus targetStatus,
                                       String auditAction,
                                       String actionVerb,
                                       String actionNoun,
                                       String successDescription,
                                       String errorReason) {
        Long reqId = parseQuoteIdSafely(quoteId);
        if (reqId == null) {
            log.warn("Cannot {} quote: invalid quoteId format | quoteId={}", actionVerb, quoteId);
            saveTransitionAudit(null, customerId, auditAction,
                    "Quote " + actionNoun + " failed: invalid quoteId format (" + quoteId + ")",
                    "FAILED", null);
            return;
        }

        PricingRequest requestEntity = pricingRepository.findById(reqId).orElse(null);
        if (requestEntity == null) {
            log.warn("Cannot {} quote: quote record not found | requestId={}", actionVerb, reqId);
            saveTransitionAudit(reqId, customerId, auditAction,
                    "Quote " + actionNoun + " failed: quote record not found",
                    "NOT_FOUND", null);
            return;
        }

        if (TERMINAL_STATUSES.contains(requestEntity.getStatus())) {
            log.warn("Cannot {} quote already in terminal state: {} | requestId={}",
                    actionVerb, requestEntity.getStatus(), reqId);
            saveTransitionAudit(reqId, customerId, auditAction,
                    "Quote " + actionNoun + " skipped: quote already in terminal state " + requestEntity.getStatus(),
                    "SKIPPED_TERMINAL_STATE", requestEntity.getTraceId());
            return;
        }

        requestEntity.setStatus(targetStatus);
        if (errorReason != null) {
            requestEntity.setErrorMessage(errorReason.length() > 500
                    ? errorReason.substring(0, 497) + "..."
                    : errorReason);
        }

        PricingRequest saved;
        try {
            saved = pricingRepository.saveAndFlush(requestEntity);
        } catch (OptimisticLockingFailureException e) {
            log.error("Optimistic lock conflict during {} transition for requestId={}: {}",
                    actionNoun, reqId, e.getMessage());
            throw e;
        }
        log.info("Updated pricing_requests status to {} | requestId={}", targetStatus, reqId);

        saveTransitionAudit(reqId, customerId, auditAction, successDescription, "SUCCESS", saved.getTraceId());
        emitStatusEvent(saved, targetStatus.name());
    }

    private void saveTransitionAudit(Long reqId, String customerId, String action,
                                     String description, String outcome, String traceId) {
        auditRepository.save(PricingAuditLog.builder()
                .pricingRequestId(reqId)
                .customerId(customerId)
                .action(action)
                .description(description)
                .performedBy("temporal-worker")
                .outcome(outcome)
                .requestIp("127.0.0.1")
                .traceId(traceId)
                .build());
    }

    private void emitStatusEvent(PricingRequest saved, String status) {
        if (outboxService != null && outboxService.isEnabled()) {
            outboxService.enqueue("PRICING_REQUEST", saved.getId(), buildStatusEvent(saved, status));
        } else if (pricingEventProducer != null) {
            publishStatusEvent(saved, status);
        }
    }

    private LoanPricingEvent buildStatusEvent(PricingRequest req, String status) {
        return LoanPricingEvent.builder()
                .requestId(req.getId())
                .customerId(req.getCustomerId())
                .productType(req.getProductType())
                .loanAmount(req.getLoanAmount())
                .interestRate(req.getCalculatedRate())
                .emiAmount(req.getEmiAmount())
                .loanTenureMonths(req.getLoanTenureMonths())
                .totalPayableAmount(req.getTotalPayableAmount())
                .totalInterestPayable(req.getTotalInterestPayable())
                .riskCategory(req.getRiskCategory())
                .status(status)
                .timestamp(Instant.now())
                .traceId(req.getTraceId())
                .build();
    }

    private void publishStatusEvent(PricingRequest req, String status) {
        LoanPricingEvent event = buildStatusEvent(req, status);

        Runnable sendTask = () -> {
            try {
                CompletableFuture<SendResult<String, Object>> publishFuture = pricingEventProducer
                        .publishPricingEvent(event);
                if (publishFuture != null) {
                    publishFuture.whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish {} event to Kafka asynchronously | requestId={} | error={}",
                                    status, req.getId(), ex.getMessage(), ex);
                        }
                    });
                }
            } catch (Exception e) {
                log.error("Failed to publish {} event to Kafka | requestId={} | error={}",
                        status, req.getId(), e.getMessage(), e);
            }
        };

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendTask.run();
                }
            });
        } else {
            sendTask.run();
        }
    }

    private Long parseQuoteIdSafely(String quoteId) {
        if (quoteId == null || quoteId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(quoteId);
        } catch (NumberFormatException e) {
            log.warn("Invalid quoteId format for audit record: {}", quoteId);
            return null;
        }
    }
}
