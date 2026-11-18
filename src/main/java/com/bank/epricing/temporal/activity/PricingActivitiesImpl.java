package com.bank.epricing.temporal.activity;

import com.bank.epricing.dto.PricingRequestDto;
import com.bank.epricing.dto.PricingResponseDto;
import com.bank.epricing.entity.PricingAuditLog;
import com.bank.epricing.entity.PricingRequest;
import com.bank.epricing.repository.PricingAuditRepository;
import com.bank.epricing.repository.PricingRepository;
import com.bank.epricing.service.PricingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of Temporal Pricing Activities.
 * Activities bridge Temporal workflow executions with Spring Boot business services.
 */
@Component
@Transactional(rollbackFor = Exception.class)
public class PricingActivitiesImpl implements PricingActivities {

    private static final Logger log = LoggerFactory.getLogger(PricingActivitiesImpl.class);

    private final PricingService pricingService;
    private final PricingAuditRepository auditRepository;
    private final PricingRepository pricingRepository;

    public PricingActivitiesImpl(PricingService pricingService,
                                 PricingAuditRepository auditRepository,
                                 PricingRepository pricingRepository) {
        this.pricingService = pricingService;
        this.auditRepository = auditRepository;
        this.pricingRepository = pricingRepository;
    }

    @Override
    public PricingResponseDto calculateAndPersistPricing(PricingRequestDto requestDto, String requestIp) {
        log.info("Executing Temporal Activity: calculateAndPersistPricing | customerId={}",
            requestDto.getCustomerId());
        return pricingService.calculatePricing(requestDto, requestIp != null ? requestIp : "127.0.0.1");
    }

    @Override
    public void recordQuoteApproval(String quoteId, String customerId) {
        log.info("Executing Temporal Activity: recordQuoteApproval | quoteId={} | customerId={}",
            quoteId, customerId);
        Long reqId = parseQuoteIdSafely(quoteId);

        if (reqId != null) {
            pricingRepository.findById(reqId).ifPresent(req -> {
                req.setStatus(PricingRequest.PricingStatus.APPROVED);
                pricingRepository.save(req);
                log.info("Updated pricing_requests status to APPROVED | requestId={}", reqId);
            });
        }

        PricingAuditLog audit = PricingAuditLog.builder()
            .pricingRequestId(reqId)
            .customerId(customerId)
            .action("RATE_APPROVED")
            .description("Customer accepted locked interest rate offer")
            .performedBy("temporal-worker")
            .outcome("SUCCESS")
            .requestIp("127.0.0.1")
            .build();
        auditRepository.save(audit);
    }

    @Override
    public void recordQuoteRejection(String quoteId, String customerId, String reason) {
        log.info("Executing Temporal Activity: recordQuoteRejection | quoteId={} | customerId={} | reason={}",
            quoteId, customerId, reason);
        Long reqId = parseQuoteIdSafely(quoteId);

        if (reqId != null) {
            pricingRepository.findById(reqId).ifPresent(req -> {
                req.setStatus(PricingRequest.PricingStatus.REJECTED);
                req.setErrorMessage(reason);
                pricingRepository.save(req);
                log.info("Updated pricing_requests status to REJECTED | requestId={}", reqId);
            });
        }

        PricingAuditLog audit = PricingAuditLog.builder()
            .pricingRequestId(reqId)
            .customerId(customerId)
            .action("RATE_REJECTED")
            .description("Quote rejected: " + reason)
            .performedBy("temporal-worker")
            .outcome("SUCCESS")
            .requestIp("127.0.0.1")
            .build();
        auditRepository.save(audit);
    }

    @Override
    public void recordQuoteExpiration(String quoteId, String customerId) {
        log.info("Executing Temporal Activity: recordQuoteExpiration | quoteId={} | customerId={}",
            quoteId, customerId);
        Long reqId = parseQuoteIdSafely(quoteId);

        if (reqId != null) {
            pricingRepository.findById(reqId).ifPresent(req -> {
                req.setStatus(PricingRequest.PricingStatus.EXPIRED);
                pricingRepository.save(req);
                log.info("Updated pricing_requests status to EXPIRED | requestId={}", reqId);
            });
        }

        PricingAuditLog audit = PricingAuditLog.builder()
            .pricingRequestId(reqId)
            .customerId(customerId)
            .action("RATE_EXPIRED")
            .description("Quote expired after validity window elapsed")
            .performedBy("temporal-worker")
            .outcome("SUCCESS")
            .requestIp("127.0.0.1")
            .build();
        auditRepository.save(audit);
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
