package com.bank.epricing.service;

import com.bank.epricing.dto.PricingRequestDto;
import com.bank.epricing.dto.PricingResponseDto;
import com.bank.epricing.entity.PricingRequest;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PricingServiceTest {

    private PricingRepository pricingRepository;
    private PricingAuditRepository auditRepository;
    private PricingCalculator pricingCalculator;
    private PricingMetrics pricingMetrics;
    private PricingAuditService auditService;
    private StructuredLogger structuredLogger;
    private Tracer tracer;
    private Span span;
    private SpanBuilder spanBuilder;

    private PricingService pricingService;

    @BeforeEach
    void setUp() {
        pricingRepository = mock(PricingRepository.class);
        auditRepository = mock(PricingAuditRepository.class);
        pricingCalculator = new PricingCalculator();
        
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        pricingMetrics = new PricingMetrics();
        pricingMetrics.bindTo(meterRegistry);

        auditService = new PricingAuditService(auditRepository, pricingRepository);
        structuredLogger = new StructuredLogger();

        tracer = mock(Tracer.class);
        span = mock(Span.class);
        spanBuilder = mock(SpanBuilder.class);

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
    @DisplayName("Should successfully calculate pricing and persist request")
    void testCalculatePricing_Success() {
        PricingRequestDto requestDto = PricingRequestDto.builder()
            .customerId("CUST001234")
            .productType("HOME_LOAN")
            .loanAmount(new BigDecimal("5000000"))
            .loanTenureMonths(240)
            .creditScore(780)
            .annualIncome(new BigDecimal("2400000"))
            .build();

        PricingRequest mockSaved = PricingRequest.builder()
            .id(100L)
            .customerId("CUST001234")
            .productType("HOME_LOAN")
            .loanAmount(new BigDecimal("5000000"))
            .loanTenureMonths(240)
            .calculatedRate(new BigDecimal("8.00"))
            .emiAmount(new BigDecimal("41822.00"))
            .status(PricingRequest.PricingStatus.CALCULATED)
            .traceId("mock-trace-id-12345")
            .build();

        when(pricingRepository.save(any(PricingRequest.class))).thenReturn(mockSaved);

        PricingResponseDto response = pricingService.calculatePricing(requestDto, "127.0.0.1");

        assertNotNull(response);
        assertEquals("CUST001234", response.getCustomerId());
        assertEquals("HOME_LOAN", response.getProductType());
        assertEquals(new BigDecimal("8.00"), response.getInterestRatePA());
        assertNotNull(response.getEmiAmount());
        assertEquals("CALCULATED", response.getStatus());

        verify(pricingRepository, times(1)).save(any(PricingRequest.class));
    }

    @Test
    @DisplayName("Should return pricing history for customer when customerId is provided")
    void testGetPricingHistory_WithCustomerId() {
        PricingRequest entity = PricingRequest.builder()
            .id(1L)
            .customerId("CUST001234")
            .productType("HOME_LOAN")
            .loanAmount(new BigDecimal("1000000"))
            .loanTenureMonths(120)
            .calculatedRate(new BigDecimal("8.50"))
            .emiAmount(new BigDecimal("12398.57"))
            .status(PricingRequest.PricingStatus.CALCULATED)
            .build();

        when(pricingRepository.findByCustomerId("CUST001234")).thenReturn(List.of(entity));

        List<PricingResponseDto> history = pricingService.getPricingHistory("CUST001234");

        assertEquals(1, history.size());
        assertEquals("CUST001234", history.get(0).getCustomerId());
        verify(pricingRepository).findByCustomerId("CUST001234");
    }

    @Test
    @DisplayName("Should return recent history when customerId is blank")
    void testGetPricingHistory_BlankCustomerId() {
        PricingRequest entity = PricingRequest.builder()
            .id(1L)
            .customerId("CUST001234")
            .productType("AUTO_LOAN")
            .loanAmount(new BigDecimal("500000"))
            .loanTenureMonths(60)
            .calculatedRate(new BigDecimal("9.50"))
            .emiAmount(new BigDecimal("10500.00"))
            .status(PricingRequest.PricingStatus.CALCULATED)
            .build();

        when(pricingRepository.findTop10ByOrderByCreatedAtDesc()).thenReturn(List.of(entity));

        List<PricingResponseDto> history = pricingService.getPricingHistory("");

        assertEquals(1, history.size());
        verify(pricingRepository).findTop10ByOrderByCreatedAtDesc();
    }

    @Test
    @DisplayName("Should return pricing details by ID")
    void testGetPricingById_Success() {
        PricingRequest entity = PricingRequest.builder()
            .id(42L)
            .customerId("CUST001234")
            .productType("HOME_LOAN")
            .loanAmount(new BigDecimal("2000000"))
            .loanTenureMonths(180)
            .calculatedRate(new BigDecimal("8.25"))
            .emiAmount(new BigDecimal("19400.00"))
            .status(PricingRequest.PricingStatus.CALCULATED)
            .build();

        when(pricingRepository.findById(42L)).thenReturn(Optional.of(entity));

        PricingResponseDto response = pricingService.getPricingById(42L);

        assertNotNull(response);
        assertEquals(42L, response.getRequestId());
        assertEquals("CUST001234", response.getCustomerId());
    }
}
