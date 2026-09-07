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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  PricingService.java — Business Logic Orchestrator                      ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║  WHY THIS CLASS EXISTS:                                                  ║
 * ║  The Service layer sits between Controller and Repository.              ║
 * ║  It contains all BUSINESS LOGIC and ORCHESTRATION:                     ║
 * ║    1. Calls PricingCalculator for the math                             ║
 * ║    2. Calls PricingRepository to persist data                          ║
 * ║    3. Records metrics via PricingMetrics                               ║
 * ║    4. Writes structured logs via StructuredLogger                      ║
 * ║    5. Creates custom OTel spans for granular tracing                   ║
 * ║    6. Calls PricingAuditService to create audit records               ║
 * ║                                                                          ║
 * ║  OBSERVABILITY IS WOVEN THROUGH THIS CLASS:                            ║
 * ║  Every significant operation records:                                   ║
 * ║    - A metric (for Prometheus/Grafana)                                 ║
 * ║    - A log (for Loki/Grafana)                                          ║
 * ║    - A span (for Tempo/Grafana)                                         ║
 * ║  All three are linked by the same traceId.                             ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */
@Service  // Marks this as a Spring-managed Service bean
public class PricingService {

    private static final Logger log = LoggerFactory.getLogger(PricingService.class);

    // All dependencies are final and injected via constructor (best practice).
    // WHY CONSTRUCTOR INJECTION (not @Autowired field injection):
    //   1. Makes dependencies explicit and visible
    //   2. Allows easy unit testing (pass mocks via constructor)
    //   3. Ensures the bean cannot be instantiated without its dependencies
    //   4. Field injection with @Autowired requires reflection and hides dependencies

    private final PricingRepository pricingRepository;
    private final PricingCalculator pricingCalculator;
    private final PricingMetrics pricingMetrics;
    private final PricingAuditService auditService;
    private final StructuredLogger structuredLogger;
    private final Tracer tracer;

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
     * ═══════════════════════════════════════════════════════════════════
     * CALCULATE PRICING — The main business operation
     * ═══════════════════════════════════════════════════════════════════
     *
     * @Transactional: Wraps this method in a database transaction.
     * If ANY exception is thrown, ALL database changes are rolled back.
     * This ensures atomicity: either everything succeeds or nothing is saved.
     *
     * readOnly=false (default): This transaction may write to the database.
     *
     * In banking, transactionality is critical:
     * "If pricing calculation succeeded but audit log failed → rollback both"
     * This prevents partial state (pricing without audit trail).
     */
    @Transactional
    public PricingResponseDto calculatePricing(PricingRequestDto requestDto, String requestIp) {

        long overallStartTime = System.currentTimeMillis();

        // ─── OBSERVABILITY: Record incoming request ────────────────────────
        pricingMetrics.recordPricingRequestReceived();
        pricingMetrics.incrementActiveRequests();
        pricingMetrics.recordProductTypeRequest(requestDto.getProductType());
        pricingMetrics.recordLoanAmount(requestDto.getLoanAmount().doubleValue());

        // Start timing the end-to-end operation
        Timer.Sample endToEndSample = pricingMetrics.startCalculationTimer();

        // ─── OBSERVABILITY: Create custom OTel span ────────────────────────
        // The outer span (for the HTTP request) is auto-created by
        // opentelemetry-spring-webmvc-6.0.
        // Here we create a CHILD SPAN specifically for the pricing business logic.
        // This gives finer granularity in the trace waterfall diagram.
        //
        // In Grafana Tempo, you'll see:
        //   └─ GET /api/v1/pricing (150ms) ← auto-created by OTel Spring MVC
        //      └─ calculatePricing (140ms)  ← THIS span (business logic)
        //         └─ validateEligibility (5ms)
        //         └─ computeRate (10ms)
        //         └─ persistPricingRequest (120ms) ← DB operation
        Span pricingSpan = tracer.spanBuilder("calculatePricing")
            .setAttribute("customer.id", requestDto.getCustomerId())
            .setAttribute("product.type", requestDto.getProductType())
            .setAttribute("loan.amount", requestDto.getLoanAmount().toPlainString())
            .startSpan();

        // try-with-resources pattern for OTel scopes:
        // Scope.makeCurrent() makes this span the "current" span for this thread.
        // When the try block exits, the scope is automatically closed and the
        // previous span becomes current again.
        // This is critical for correct parent-child span relationships.
        try (Scope scope = pricingSpan.makeCurrent()) {

            structuredLogger.logPricingStarted(
                requestDto.getCustomerId(),
                requestDto.getProductType(),
                requestDto.getLoanAmount()
            );

            // ─── STEP 1: Validate eligibility ──────────────────────────────
            // Creates a child span: "validateEligibility"
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
                // Business rejection — record in metrics and audit, then rethrow
                validationSpan.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.getMessage());
                pricingMetrics.decrementActiveRequests();
                pricingMetrics.recordPricingRejection();
                structuredLogger.logPricingRejected(
                    requestDto.getCustomerId(), requestDto.getProductType(),
                    e.getMessage(), e.getErrorCode()
                );
                auditService.recordRejection(requestDto, e.getErrorCode(), requestIp,
                    Span.current().getSpanContext().getTraceId());
                throw e; // Rethrow so GlobalExceptionHandler formats the response
            } finally {
                validationSpan.end();
            }

            // ─── STEP 2: Calculate interest rate ──────────────────────────
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

            // ─── STEP 3: Calculate EMI ─────────────────────────────────────
            BigDecimal emi = pricingCalculator.calculateEmi(
                requestDto.getLoanAmount(),
                interestRate,
                requestDto.getLoanTenureMonths()
            );

            // ─── STEP 4: Calculate derived values ─────────────────────────
            BigDecimal totalPayable = emi.multiply(BigDecimal.valueOf(requestDto.getLoanTenureMonths()));
            BigDecimal totalInterest = totalPayable.subtract(requestDto.getLoanAmount());
            String riskCategory = pricingCalculator.determineRiskCategory(requestDto.getCreditScore());

            // ─── STEP 5: Persist to database ──────────────────────────────
            String traceId = pricingSpan.getSpanContext().getTraceId();
            long dbStartTime = System.currentTimeMillis();

            // Repository Layer Span
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

            // ─── STEP 6: Create audit record ──────────────────────────────
            auditService.recordSuccess(savedRequest, requestIp, traceId);

            // ─── STEP 7: Record success metrics ───────────────────────────
            long totalDuration = System.currentTimeMillis() - overallStartTime;
            savedRequest.setProcessingTimeMs(totalDuration);
            pricingMetrics.recordPricingSuccess();
            pricingMetrics.decrementActiveRequests();
            pricingMetrics.recordEndToEndDuration(endToEndSample);

            // Add result attributes to the span
            pricingSpan.setAttribute("result.rate", interestRate.toPlainString());
            pricingSpan.setAttribute("result.emi", emi.toPlainString());
            pricingSpan.setAttribute("result.risk_category", riskCategory);
            pricingSpan.addEvent("Pricing calculation completed successfully");

            structuredLogger.logPricingCompleted(
                requestDto.getCustomerId(), requestDto.getProductType(),
                interestRate, emi, totalDuration
            );

            // ─── STEP 8: Build and return response ────────────────────────
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
                .rateValidUntil(LocalDateTime.now().plusDays(30))
                .build();

        } catch (PricingException e) {
            pricingSpan.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.getMessage());
            pricingMetrics.decrementActiveRequests();
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
            // CRITICAL GAP FIX: Persist a FAILED record to DB in a NEW transaction
            // so Grafana's L1 Jobs Table shows the error without anyone logging into DB.
            // Uses REQUIRES_NEW propagation so this commit is independent of the rolled-back main tx.
            auditService.recordTechnicalFailure(requestDto, e.getMessage(), requestIp,
                pricingSpan.getSpanContext().getTraceId());
            throw new PricingException.PricingCalculationException(
                "Unexpected error during pricing", e
            );
        } finally {
            // ALWAYS end the span — even if an exception was thrown
            pricingSpan.end();
        }
    }

    /**
     * Retrieve all pricing requests for a customer (or recent history if customerId is blank).
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
     * Retrieve paginated pricing requests for a customer.
     */
    @Transactional(readOnly = true)
    public Page<PricingResponseDto> getPricingHistory(String customerId, Pageable pageable) {
        log.info("Fetching paginated pricing history | customerId={} | page={} | size={}",
            customerId, pageable.getPageNumber(), pageable.getPageSize());
        return pricingRepository.findByCustomerId(customerId, pageable)
            .map(this::mapToResponseDto);
    }

    /**
     * Get a pricing request by its ID.
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

    /**
     * Maps entity to response DTO — keeps controller and repository decoupled.
     */
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
            .rateValidUntil(entity.getCreatedAt() != null ? entity.getCreatedAt().plusDays(30) : LocalDateTime.now().plusDays(30))
            .build();
    }
}
