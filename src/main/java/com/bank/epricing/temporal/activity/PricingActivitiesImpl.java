package com.bank.epricing.temporal.activity;

import com.bank.epricing.dto.PricingRequestDto;
import com.bank.epricing.dto.PricingResponseDto;
import com.bank.epricing.entity.PricingAuditLog;
import com.bank.epricing.entity.PricingRequest;
import com.bank.epricing.event.LoanPricingEvent;
import com.bank.epricing.logging.StructuredLogger;
import com.bank.epricing.repository.PricingAuditRepository;
import com.bank.epricing.repository.PricingRepository;
import com.bank.epricing.service.PricingEventProducer;
import com.bank.epricing.service.PricingService;
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
 * Activities bridge Temporal workflow executions with Spring Boot business services,
 * YugabyteDB transactional persistence, and Apache Kafka domain event streaming.
 *
 * ISSUE-01 FIX: Removed class-level @Transactional to eliminate outer transaction spanning
 * calculateAndPersistPricing, which caused HikariCP connection pool starvation. Discrete update
 * methods manage their own short-lived transactions.
 *
 * ISSUE-02 FIX: Kafka event publishing in publishStatusEvent is deferred to afterCommit()
 * when an active database transaction is present, preventing dual-write hazards and connection pinning.
 *
 * ISSUE-03 FIX: Added async whenComplete callback to publishPricingEvent to ensure failures are logged.
 *
 * ISSUE-07 FIX: Handles OptimisticLockingFailureException gracefully on concurrent state transitions.
 *
 * ISSUE-08 FIX: Generates UTC Instant timestamps for domain events.
 */
@Component
public class PricingActivitiesImpl implements PricingActivities {

    private static final Logger log = LoggerFactory.getLogger(PricingActivitiesImpl.class);

    private static final Set<PricingRequest.PricingStatus> TERMINAL_STATUSES = EnumSet.of(
            PricingRequest.PricingStatus.APPROVED,
            PricingRequest.PricingStatus.REJECTED,
            PricingRequest.PricingStatus.EXPIRED
    );

    private final PricingService pricingService;
    private final PricingAuditRepository auditRepository;
    private final PricingRepository pricingRepository;
    private final PricingEventProducer pricingEventProducer;

    @Autowired
    public PricingActivitiesImpl(PricingService pricingService,
                                 PricingAuditRepository auditRepository,
                                 PricingRepository pricingRepository,
                                 @Autowired(required = false) PricingEventProducer pricingEventProducer) {
        this.pricingService = pricingService;
        this.auditRepository = auditRepository;
        this.pricingRepository = pricingRepository;
        this.pricingEventProducer = pricingEventProducer;
    }

    public PricingActivitiesImpl(PricingService pricingService,
                                 PricingAuditRepository auditRepository,
                                 PricingRepository pricingRepository) {
        this(pricingService, auditRepository, pricingRepository, null);
    }

    @Override
    public PricingResponseDto calculateAndPersistPricing(PricingRequestDto requestDto, String requestIp) {
        log.info("Executing Temporal Activity: calculateAndPersistPricing | customerId={}",
                StructuredLogger.maskCustomerId(requestDto.getCustomerId()));
        return pricingService.calculatePricing(requestDto, requestIp != null ? requestIp : "127.0.0.1");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordQuoteApproval(String quoteId, String customerId) {
        log.info("Executing Temporal Activity: recordQuoteApproval | quoteId={} | customerId={}",
                quoteId, StructuredLogger.maskCustomerId(customerId));
        Long reqId = parseQuoteIdSafely(quoteId);

        if (reqId == null) {
            log.warn("Cannot approve quote: invalid quoteId format | quoteId={}", quoteId);
            PricingAuditLog audit = PricingAuditLog.builder()
                    .customerId(customerId)
                    .action("RATE_APPROVED")
                    .description("Quote approval failed: invalid quoteId format (" + quoteId + ")")
                    .performedBy("temporal-worker")
                    .outcome("FAILED")
                    .requestIp("127.0.0.1")
                    .build();
            auditRepository.save(audit);
            return;
        }

        PricingRequest requestEntity = pricingRepository.findById(reqId).orElse(null);

        if (requestEntity == null) {
            log.warn("Cannot approve quote: quote record not found | requestId={}", reqId);
            PricingAuditLog audit = PricingAuditLog.builder()
                    .pricingRequestId(reqId)
                    .customerId(customerId)
                    .action("RATE_APPROVED")
                    .description("Quote approval failed: quote record not found")
                    .performedBy("temporal-worker")
                    .outcome("NOT_FOUND")
                    .requestIp("127.0.0.1")
                    .build();
            auditRepository.save(audit);
            return;
        }

        if (TERMINAL_STATUSES.contains(requestEntity.getStatus())) {
            log.warn("Cannot approve quote already in terminal state: {} | requestId={}", requestEntity.getStatus(), reqId);
            PricingAuditLog audit = PricingAuditLog.builder()
                    .pricingRequestId(reqId)
                    .customerId(customerId)
                    .action("RATE_APPROVED")
                    .description("Quote approval skipped: quote already in terminal state " + requestEntity.getStatus())
                    .performedBy("temporal-worker")
                    .outcome("SKIPPED_TERMINAL_STATE")
                    .requestIp("127.0.0.1")
                    .traceId(requestEntity.getTraceId())
                    .build();
            auditRepository.save(audit);
            return;
        }

        requestEntity.setStatus(PricingRequest.PricingStatus.APPROVED);
        PricingRequest saved;
        try {
            saved = pricingRepository.saveAndFlush(requestEntity);
        } catch (OptimisticLockingFailureException e) {
            log.error("Optimistic lock conflict during recordQuoteApproval for requestId={}: {}", reqId, e.getMessage());
            throw e;
        }
        log.info("Updated pricing_requests status to APPROVED | requestId={}", reqId);

        PricingAuditLog audit = PricingAuditLog.builder()
                .pricingRequestId(reqId)
                .customerId(customerId)
                .action("RATE_APPROVED")
                .description("Customer accepted locked interest rate offer")
                .performedBy("temporal-worker")
                .outcome("SUCCESS")
                .requestIp("127.0.0.1")
                .traceId(saved.getTraceId())
                .build();
        auditRepository.save(audit);

        if (pricingEventProducer != null) {
            publishStatusEvent(saved, "APPROVED");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordQuoteRejection(String quoteId, String customerId, String reason) {
        log.info("Executing Temporal Activity: recordQuoteRejection | quoteId={} | customerId={} | reason={}",
                quoteId, StructuredLogger.maskCustomerId(customerId), reason);
        Long reqId = parseQuoteIdSafely(quoteId);

        if (reqId == null) {
            log.warn("Cannot reject quote: invalid quoteId format | quoteId={}", quoteId);
            PricingAuditLog audit = PricingAuditLog.builder()
                    .customerId(customerId)
                    .action("RATE_REJECTED")
                    .description("Quote rejection failed: invalid quoteId format (" + quoteId + ")")
                    .performedBy("temporal-worker")
                    .outcome("FAILED")
                    .requestIp("127.0.0.1")
                    .build();
            auditRepository.save(audit);
            return;
        }

        PricingRequest requestEntity = pricingRepository.findById(reqId).orElse(null);

        if (requestEntity == null) {
            log.warn("Cannot reject quote: quote record not found | requestId={}", reqId);
            PricingAuditLog audit = PricingAuditLog.builder()
                    .pricingRequestId(reqId)
                    .customerId(customerId)
                    .action("RATE_REJECTED")
                    .description("Quote rejection failed: quote record not found")
                    .performedBy("temporal-worker")
                    .outcome("NOT_FOUND")
                    .requestIp("127.0.0.1")
                    .build();
            auditRepository.save(audit);
            return;
        }

        if (TERMINAL_STATUSES.contains(requestEntity.getStatus())) {
            log.warn("Cannot reject quote already in terminal state: {} | requestId={}", requestEntity.getStatus(), reqId);
            PricingAuditLog audit = PricingAuditLog.builder()
                    .pricingRequestId(reqId)
                    .customerId(customerId)
                    .action("RATE_REJECTED")
                    .description("Quote rejection skipped: quote already in terminal state " + requestEntity.getStatus())
                    .performedBy("temporal-worker")
                    .outcome("SKIPPED_TERMINAL_STATE")
                    .requestIp("127.0.0.1")
                    .traceId(requestEntity.getTraceId())
                    .build();
            auditRepository.save(audit);
            return;
        }

        requestEntity.setStatus(PricingRequest.PricingStatus.REJECTED);
        String safeReason = (reason != null && reason.length() > 500)
            ? reason.substring(0, 497) + "..."
            : reason;
        requestEntity.setErrorMessage(safeReason);
        PricingRequest saved;
        try {
            saved = pricingRepository.saveAndFlush(requestEntity);
        } catch (OptimisticLockingFailureException e) {
            log.error("Optimistic lock conflict during recordQuoteRejection for requestId={}: {}", reqId, e.getMessage());
            throw e;
        }
        log.info("Updated pricing_requests status to REJECTED | requestId={}", reqId);

        PricingAuditLog audit = PricingAuditLog.builder()
                .pricingRequestId(reqId)
                .customerId(customerId)
                .action("RATE_REJECTED")
                .description("Quote rejected: " + reason)
                .performedBy("temporal-worker")
                .outcome("SUCCESS")
                .requestIp("127.0.0.1")
                .traceId(saved.getTraceId())
                .build();
        auditRepository.save(audit);

        if (pricingEventProducer != null) {
            publishStatusEvent(saved, "REJECTED");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordQuoteExpiration(String quoteId, String customerId) {
        log.info("Executing Temporal Activity: recordQuoteExpiration | quoteId={} | customerId={}",
                quoteId, StructuredLogger.maskCustomerId(customerId));
        Long reqId = parseQuoteIdSafely(quoteId);

        if (reqId == null) {
            log.warn("Cannot expire quote: invalid quoteId format | quoteId={}", quoteId);
            PricingAuditLog audit = PricingAuditLog.builder()
                    .customerId(customerId)
                    .action("RATE_EXPIRED")
                    .description("Quote expiration failed: invalid quoteId format (" + quoteId + ")")
                    .performedBy("temporal-worker")
                    .outcome("FAILED")
                    .requestIp("127.0.0.1")
                    .build();
            auditRepository.save(audit);
            return;
        }

        PricingRequest requestEntity = pricingRepository.findById(reqId).orElse(null);

        if (requestEntity == null) {
            log.warn("Cannot expire quote: quote record not found | requestId={}", reqId);
            PricingAuditLog audit = PricingAuditLog.builder()
                    .pricingRequestId(reqId)
                    .customerId(customerId)
                    .action("RATE_EXPIRED")
                    .description("Quote expiration failed: quote record not found")
                    .performedBy("temporal-worker")
                    .outcome("NOT_FOUND")
                    .requestIp("127.0.0.1")
                    .build();
            auditRepository.save(audit);
            return;
        }

        if (TERMINAL_STATUSES.contains(requestEntity.getStatus())) {
            log.warn("Cannot expire quote already in terminal state: {} | requestId={}", requestEntity.getStatus(), reqId);
            PricingAuditLog audit = PricingAuditLog.builder()
                    .pricingRequestId(reqId)
                    .customerId(customerId)
                    .action("RATE_EXPIRED")
                    .description("Quote expiration skipped: quote already in terminal state " + requestEntity.getStatus())
                    .performedBy("temporal-worker")
                    .outcome("SKIPPED_TERMINAL_STATE")
                    .requestIp("127.0.0.1")
                    .traceId(requestEntity.getTraceId())
                    .build();
            auditRepository.save(audit);
            return;
        }

        requestEntity.setStatus(PricingRequest.PricingStatus.EXPIRED);
        PricingRequest saved;
        try {
            saved = pricingRepository.saveAndFlush(requestEntity);
        } catch (OptimisticLockingFailureException e) {
            log.error("Optimistic lock conflict during recordQuoteExpiration for requestId={}: {}", reqId, e.getMessage());
            throw e;
        }
        log.info("Updated pricing_requests status to EXPIRED | requestId={}", reqId);

        PricingAuditLog audit = PricingAuditLog.builder()
                .pricingRequestId(reqId)
                .customerId(customerId)
                .action("RATE_EXPIRED")
                .description("Quote expired after validity window elapsed")
                .performedBy("temporal-worker")
                .outcome("SUCCESS")
                .requestIp("127.0.0.1")
                .traceId(saved.getTraceId())
                .build();
        auditRepository.save(audit);

        if (pricingEventProducer != null) {
            publishStatusEvent(saved, "EXPIRED");
        }
    }

    private void publishStatusEvent(PricingRequest req, String status) {
        LoanPricingEvent event = LoanPricingEvent.builder()
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

        Runnable sendTask = () -> {
            try {
                CompletableFuture<SendResult<String, Object>> publishFuture =
                        pricingEventProducer.publishPricingEvent(event);
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
