package com.bank.epricing.service;

import com.bank.epricing.dto.PricingRequestDto;
import com.bank.epricing.dto.PricingResponseDto;
import com.bank.epricing.entity.PricingRequest;
import com.bank.epricing.exception.PricingException;
import com.bank.epricing.logging.StructuredLogger;
import com.bank.epricing.metrics.PricingMetrics;
import com.bank.epricing.repository.PricingRepository;
import com.bank.epricing.util.PricingCalculator;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PricingService {

    private static final Logger log = LoggerFactory.getLogger(PricingService.class);

    private final PricingRepository pricingRepository;
    private final PricingCalculator pricingCalculator;
    private final PricingMetrics pricingMetrics;
    private final PricingAuditService auditService;
    private final StructuredLogger structuredLogger;
    private final Tracer tracer;

    /** How long (days) a quoted rate is valid — configurable via epricing.rate-valid-days */
    @Value("${epricing.rate-valid-days:7}")
    private int rateValidDays;

    public PricingService(PricingRepository pricingRepository,
                          PricingCalculator pricingCalculator,
                          PricingMetrics pricingMetrics,
                          PricingAuditService auditService,
                          StructuredLogger structuredLogger,
                          Tracer tracer) {
        this.pricingRepository = pricingRepository;
        this.pricingCalculator = pricingCalculator;
        this.pricingMetrics = pricingMetrics;
        this.auditService = auditService;
        this.structuredLogger = structuredLogger;
        this.tracer = tracer;
    }

    /**
     * Main pricing calculation flow.
     * @Transactional ensures pricing record + audit record are committed together.
     * If audit fails, the whole transaction rolls back — no orphaned pricing records.
     */
    @Transactional
    public PricingResponseDto calculatePricing(PricingRequestDto requestDto, String requestIp) {

        long overallStartTime = System.currentTimeMillis();

        pricingMetrics.recordPricingRequestReceived();
        pricingMetrics.incrementActiveRequests();
        pricingMetrics.recordProductTypeRequest(requestDto.getProductType());
        pricingMetrics.recordLoanAmount(requestDto.getLoanAmount().doubleValue());

        Timer.Sample endToEndSample = pricingMetrics.startCalculationTimer();

        // Parent span for the business logic — sits under the auto-created HTTP span in Tempo
        Span pricingSpan = tracer.spanBuilder("calculatePricing")
            .setAttribute("customer.id", requestDto.getCustomerId())
            .setAttribute("product.type", requestDto.getProductType())
            .setAttribute("loan.amount", requestDto.getLoanAmount().toPlainString())
            .startSpan();

        try (Scope scope = pricingSpan.makeCurrent()) {

            structuredLogger.logPricingStarted(
                requestDto.getCustomerId(),
                requestDto.getProductType(),
                requestDto.getLoanAmount()
            );

            // Step 1: eligibility validation
            Span validationSpan = tracer.spanBuilder("validateEligibility")
                .setAttribute("credit.score", requestDto.getCreditScore() != null
                    ? requestDto.getCreditScore().toString() : "not_provided")
                .startSpan();
            try (Scope validationScope = validationSpan.makeCurrent()) {
                pricingCalculator.validateEligibility(
                    requestDto.getCustomerId(),
                    requestDto.getCreditScore(),
                    requestDto.getLoanAmount(),
                    requestDto.getAnnualIncome()
                );
                validationSpan.addEvent("Eligibility validation passed");
            } catch (PricingException.InsufficientCreditScoreException |
                     PricingException.LoanAmountExceedsEligibilityException e) {
                validationSpan.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.getMessage());
                pricingMetrics.decrementActiveRequests();
                pricingMetrics.recordPricingRejection();
                structuredLogger.logPricingRejected(
                    requestDto.getCustomerId(), requestDto.getProductType(),
                    e.getMessage(), e.getErrorCode()
                );
                auditService.recordRejection(requestDto, e.getMessage(), e.getErrorCode(), requestIp,
                    Span.current().getSpanContext().getTraceId());
                throw e;
            } finally {
                validationSpan.end();
            }

            // Step 2: interest rate calculation
            Timer.Sample calcSample = pricingMetrics.startCalculationTimer();
            Span calcSpan = tracer.spanBuilder("computeInterestRate").startSpan();
            BigDecimal interestRate;
            try (Scope calcScope = calcSpan.makeCurrent()) {
                interestRate = pricingCalculator.calculateInterestRate(
                    requestDto.getProductType(),
                    requestDto.getCreditScore(),
                    requestDto.getLoanAmount()
                );
                calcSpan.setAttribute("calculated.rate", interestRate.toPlainString());
                calcSpan.addEvent("Interest rate computed successfully");
            } finally {
                calcSpan.end();
                pricingMetrics.stopCalculationTimer(calcSample);
            }

            // Step 3: EMI and derived values
            BigDecimal emi = pricingCalculator.calculateEmi(
                requestDto.getLoanAmount(),
                interestRate,
                requestDto.getLoanTenureMonths()
            );
            BigDecimal totalPayable = emi.multiply(BigDecimal.valueOf(requestDto.getLoanTenureMonths()));
            BigDecimal totalInterest = totalPayable.subtract(requestDto.getLoanAmount());
            String riskCategory = pricingCalculator.determineRiskCategory(requestDto.getCreditScore());

            // Step 4: persist to DB
            String traceId = pricingSpan.getSpanContext().getTraceId();
            long dbStartTime = System.currentTimeMillis();

            Span dbSpan = tracer.spanBuilder("PricingRepository.save")
                .setAttribute("db.operation", "INSERT")
                .setAttribute("db.table", "pricing_requests")
                .startSpan();

            PricingRequest savedRequest;
            try (Scope dbScope = dbSpan.makeCurrent()) {
                long currentDuration = System.currentTimeMillis() - overallStartTime;
                PricingRequest entity = PricingRequest.builder()
                    .customerId(requestDto.getCustomerId())
                    .productType(requestDto.getProductType())
                    .loanAmount(requestDto.getLoanAmount())
                    .loanTenureMonths(requestDto.getLoanTenureMonths())
                    .creditScore(requestDto.getCreditScore())
                    .annualIncome(requestDto.getAnnualIncome())
                    .loanPurpose(requestDto.getLoanPurpose())
                    .totalPayableAmount(totalPayable)
                    .totalInterestPayable(totalInterest)
                    .riskCategory(riskCategory)
                    .calculatedRate(interestRate)
                    .emiAmount(emi)
                    .status(PricingRequest.PricingStatus.CALCULATED)
                    .processingTimeMs(currentDuration)
                    .requestIp(requestIp)
                    .traceId(traceId)
                    .build();

                savedRequest = pricingRepository.save(entity);

                long dbDuration = System.currentTimeMillis() - dbStartTime;
                dbSpan.setAttribute("db.row.id", savedRequest.getId().toString());
                dbSpan.addEvent("Pricing request persisted to database");

                structuredLogger.logDatabaseOperation("INSERT", "PricingRequest", dbDuration, true);
            } catch (Exception e) {
                dbSpan.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, "Database save failed: " + e.getMessage());
                structuredLogger.logDatabaseOperation("INSERT", "PricingRequest", System.currentTimeMillis() - dbStartTime, false);
                throw e;
            } finally {
                dbSpan.end();
            }

            // Step 5: audit trail (runs in same transaction — must not fail silently)
            auditService.recordSuccess(savedRequest, requestIp, traceId);

            // Step 6: finalize metrics and span attributes
            long totalDuration = System.currentTimeMillis() - overallStartTime;
            savedRequest.setProcessingTimeMs(totalDuration);
            pricingMetrics.recordPricingSuccess();
            pricingMetrics.decrementActiveRequests();
            pricingMetrics.recordEndToEndDuration(endToEndSample);

            pricingSpan.setAttribute("result.rate", interestRate.toPlainString());
            pricingSpan.setAttribute("result.emi", emi.toPlainString());
            pricingSpan.setAttribute("result.risk_category", riskCategory);
            pricingSpan.addEvent("Pricing calculation completed successfully");

            structuredLogger.logPricingCompleted(
                requestDto.getCustomerId(), requestDto.getProductType(),
                interestRate, emi, totalDuration
            );

            return PricingResponseDto.builder()
                .requestId(savedRequest.getId())
                .customerId(savedRequest.getCustomerId())
                .productType(savedRequest.getProductType())
                .loanAmount(savedRequest.getLoanAmount())
                .loanTenureMonths(savedRequest.getLoanTenureMonths())
                .interestRatePA(interestRate)
                .emiAmount(emi)
                .totalPayableAmount(totalPayable)
                .totalInterestPayable(totalInterest)
                .status(PricingRequest.PricingStatus.CALCULATED.name())
                .message(String.format(
                    "Pricing calculated successfully. Interest rate: %.2f%% p.a. EMI: ₹%.2f/month",
                    interestRate, emi
                ))
                .traceId(traceId)
                .processingTimeMs(totalDuration)
                .calculatedAt(LocalDateTime.now())
                .riskCategory(riskCategory)
                .rateValidUntil(LocalDateTime.now().plusDays(rateValidDays))
                .build();

        } catch (PricingException e) {
            pricingSpan.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.getMessage());
            pricingMetrics.decrementActiveRequests();
            pricingMetrics.recordPricingFailure();
            auditService.recordTechnicalFailure(requestDto, e.getMessage(), requestIp,
                pricingSpan.getSpanContext().getTraceId());
            throw e;
        } catch (Exception e) {
            pricingSpan.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, "Unexpected error");
            pricingSpan.recordException(e);
            pricingMetrics.recordPricingFailure();
            pricingMetrics.decrementActiveRequests();
            structuredLogger.logPricingError(
                requestDto.getCustomerId(), requestDto.getProductType(),
                e.getMessage(), e
            );
            // Persist FAILED record in a new transaction so the L1 dashboard shows the error
            String failureDetail = (e.getMessage() != null && !e.getMessage().isBlank())
                ? e.getMessage()
                : e.getClass().getSimpleName();
            auditService.recordTechnicalFailure(requestDto, failureDetail, requestIp,
                pricingSpan.getSpanContext().getTraceId());
            throw new PricingException.PricingCalculationException(
                "Unexpected error during pricing", e
            );
        } finally {
            pricingSpan.end();
        }
    }

    /**
     * Returns pricing history for a customer, or last 10 records if customerId is blank.
     */
    @Transactional(readOnly = true)
    public List<PricingResponseDto> getPricingHistory(String customerId) {
        log.info("Fetching pricing history | customerId={}", customerId);
        Span repoSpan = tracer.spanBuilder("PricingRepository.findByCustomerId")
            .setAttribute("db.operation", "SELECT")
            .setAttribute("db.table", "pricing_requests")
            .startSpan();

        try (Scope scope = repoSpan.makeCurrent()) {
            List<PricingRequest> requests;
            if (customerId == null || customerId.isBlank()) {
                requests = pricingRepository.findTop10ByOrderByCreatedAtDesc();
            } else {
                requests = pricingRepository.findByCustomerId(customerId);
            }
            repoSpan.setAttribute("result.count", requests.size());
            return requests.stream()
                .map(this::mapToResponseDto)
                .collect(Collectors.toList());
        } finally {
            repoSpan.end();
        }
    }

    /**
     * Returns paginated pricing history for a customer.
     */
    @Transactional(readOnly = true)
    public Page<PricingResponseDto> getPricingHistory(String customerId, Pageable pageable) {
        log.info("Fetching paginated pricing history | customerId={} | page={} | size={}",
            customerId, pageable.getPageNumber(), pageable.getPageSize());
        return pricingRepository.findByCustomerId(customerId, pageable)
            .map(this::mapToResponseDto);
    }

    /**
     * Returns a single pricing record by DB ID.
     */
    @Transactional(readOnly = true)
    public PricingResponseDto getPricingById(Long id) {
        log.info("Fetching pricing by ID | id={}", id);
        Span repoSpan = tracer.spanBuilder("PricingRepository.findById")
            .setAttribute("db.operation", "SELECT")
            .setAttribute("db.table", "pricing_requests")
            .setAttribute("db.row.id", id.toString())
            .startSpan();

        try (Scope scope = repoSpan.makeCurrent()) {
            return pricingRepository.findById(id)
                .map(this::mapToResponseDto)
                .orElseThrow(() -> new PricingException.PricingRequestNotFoundException(id));
        } finally {
            repoSpan.end();
        }
    }

    private PricingResponseDto mapToResponseDto(PricingRequest entity) {
        BigDecimal loanAmount = entity.getLoanAmount() != null ? entity.getLoanAmount() : BigDecimal.ZERO;
        BigDecimal emi = entity.getEmiAmount() != null ? entity.getEmiAmount() : BigDecimal.ZERO;
        int tenure = entity.getLoanTenureMonths() != null ? entity.getLoanTenureMonths() : 12;

        BigDecimal totalPayable = entity.getTotalPayableAmount();
        if (totalPayable == null && emi.compareTo(BigDecimal.ZERO) > 0) {
            totalPayable = emi.multiply(BigDecimal.valueOf(tenure));
        }

        BigDecimal totalInterest = entity.getTotalInterestPayable();
        if (totalInterest == null && totalPayable != null) {
            totalInterest = totalPayable.subtract(loanAmount);
        }

        String riskCategory = entity.getRiskCategory();
        if (riskCategory == null) {
            riskCategory = pricingCalculator.determineRiskCategory(entity.getCreditScore());
        }

        return PricingResponseDto.builder()
            .requestId(entity.getId())
            .customerId(entity.getCustomerId())
            .productType(entity.getProductType())
            .loanAmount(loanAmount)
            .loanTenureMonths(tenure)
            .interestRatePA(entity.getCalculatedRate())
            .emiAmount(emi)
            .totalPayableAmount(totalPayable)
            .totalInterestPayable(totalInterest)
            .status(entity.getStatus() != null ? entity.getStatus().name() : "UNKNOWN")
            .message(String.format("Pricing record ID %d for customer %s", entity.getId(), entity.getCustomerId()))
            .processingTimeMs(entity.getProcessingTimeMs())
            .calculatedAt(entity.getCreatedAt())
            .traceId(entity.getTraceId())
            .riskCategory(riskCategory)
            .rateValidUntil(entity.getCreatedAt() != null
                ? entity.getCreatedAt().plusDays(rateValidDays)
                : LocalDateTime.now().plusDays(rateValidDays))
            .build();
    }
}
