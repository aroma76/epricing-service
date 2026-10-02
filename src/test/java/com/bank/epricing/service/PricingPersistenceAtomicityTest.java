package com.bank.epricing.service;

import com.bank.epricing.dto.PricingRequestDto;
import com.bank.epricing.entity.PricingAuditLog;
import com.bank.epricing.entity.PricingRequest;
import com.bank.epricing.exception.PricingException;
import com.bank.epricing.logging.StructuredLogger;
import com.bank.epricing.metrics.PricingMetrics;
import com.bank.epricing.repository.PricingAuditRepository;
import com.bank.epricing.repository.PricingRepository;
import com.bank.epricing.util.PricingCalculator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PricingPersistenceAtomicityTest {

    private PricingRepository pricingRepository;
    private PricingAuditRepository auditRepository;
    private PricingAuditService auditService;
    private PricingService pricingService;

    @BeforeEach
    void setUp() {
        pricingRepository = mock(PricingRepository.class);
        auditRepository = mock(PricingAuditRepository.class);
        PricingCalculator pricingCalculator = new PricingCalculator();

        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        PricingMetrics pricingMetrics = new PricingMetrics();
        pricingMetrics.bindTo(meterRegistry);

        auditService = new PricingAuditService(auditRepository, pricingRepository);
        StructuredLogger structuredLogger = new StructuredLogger();

        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        SpanBuilder spanBuilder = mock(SpanBuilder.class);
        SpanContext spanContext = mock(SpanContext.class);

        when(spanContext.getTraceId()).thenReturn("mock-trace-id-12345");
        when(span.getSpanContext()).thenReturn(spanContext);
        when(spanBuilder.setAttribute(anyString(), anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setAttribute(anyString(), anyLong())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(span);
        when(tracer.spanBuilder(anyString())).thenReturn(spanBuilder);

        pricingService = new PricingService(
            pricingRepository,
            pricingCalculator,
            pricingMetrics,
            auditService,
            structuredLogger,
            tracer
        );
    }

    @Test
    @DisplayName("savePricingWithAudit must persist both PricingRequest and PricingAuditLog")
    void testSavePricingWithAudit_Success() {
        PricingRequest entity = PricingRequest.builder()
            .customerId("CUST001234")
            .productType("HOME_LOAN")
            .loanAmount(new BigDecimal("5000000"))
            .loanTenureMonths(240)
            .calculatedRate(new BigDecimal("8.00"))
            .emiAmount(new BigDecimal("41822.00"))
            .status(PricingRequest.PricingStatus.CALCULATED)
            .processingTimeMs(25L)
            .build();

        when(pricingRepository.save(any(PricingRequest.class))).thenAnswer(inv -> {
            PricingRequest req = inv.getArgument(0);
            req.setId(101L);
            return req;
        });

        PricingRequest saved = auditService.savePricingWithAudit(entity, "127.0.0.1", "test-trace");

        assertNotNull(saved);
        assertEquals(101L, saved.getId());
        verify(pricingRepository, times(1)).save(entity);
        verify(auditRepository, times(1)).save(argThat(audit ->
            audit.getPricingRequestId().equals(101L) &&
            audit.getCustomerId().equals("CUST001234") &&
            audit.getAction().equals("PRICING_CALCULATED") &&
            audit.getOutcome().equals("SUCCESS")
        ));
    }

    @Test
    @DisplayName("savePricingWithAudit must propagate audit failure and not swallow exception")
    void testSavePricingWithAudit_AuditFailurePropagates() {
        PricingRequest entity = PricingRequest.builder()
            .customerId("CUST001234")
            .productType("HOME_LOAN")
            .loanAmount(new BigDecimal("5000000"))
            .loanTenureMonths(240)
            .calculatedRate(new BigDecimal("8.00"))
            .emiAmount(new BigDecimal("41822.00"))
            .status(PricingRequest.PricingStatus.CALCULATED)
            .processingTimeMs(25L)
            .build();

        when(pricingRepository.save(any(PricingRequest.class))).thenAnswer(inv -> {
            PricingRequest req = inv.getArgument(0);
            req.setId(102L);
            return req;
        });
        when(auditRepository.save(any(PricingAuditLog.class)))
            .thenThrow(new DataIntegrityViolationException("Audit constraint violation"));

        assertThrows(DataIntegrityViolationException.class, () ->
            auditService.savePricingWithAudit(entity, "127.0.0.1", "test-trace")
        );

        verify(pricingRepository, times(1)).save(entity);
        verify(auditRepository, times(1)).save(any(PricingAuditLog.class));
    }

    @Test
    @DisplayName("calculatePricing must fail cleanly when atomic persistence fails")
    void testCalculatePricing_PersistenceFailureRethrown() {
        PricingRequestDto requestDto = PricingRequestDto.builder()
            .customerId("CUST001234")
            .productType("HOME_LOAN")
            .loanAmount(new BigDecimal("5000000"))
            .loanTenureMonths(240)
            .creditScore(780)
            .annualIncome(new BigDecimal("2400000"))
            .build();

        when(pricingRepository.save(any(PricingRequest.class)))
            .thenThrow(new RuntimeException("Database down"));

        assertThrows(PricingException.PricingCalculationException.class, () ->
            pricingService.calculatePricing(requestDto, "127.0.0.1")
        );
    }
}
