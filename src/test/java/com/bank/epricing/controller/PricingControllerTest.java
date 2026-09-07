package com.bank.epricing.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.bank.epricing.dto.PricingRequestDto;
import com.bank.epricing.entity.PricingRequest;
import com.bank.epricing.exception.GlobalExceptionHandler;
import com.bank.epricing.logging.StructuredLogger;
import com.bank.epricing.metrics.PricingMetrics;
import com.bank.epricing.repository.PricingAuditRepository;
import com.bank.epricing.repository.PricingRepository;
import com.bank.epricing.service.PricingAuditService;
import com.bank.epricing.service.PricingService;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PricingControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private PricingRepository pricingRepository;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        pricingRepository = mock(PricingRepository.class);
        PricingAuditRepository auditRepository = mock(PricingAuditRepository.class);
        PricingCalculator pricingCalculator = new PricingCalculator();

        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        PricingMetrics pricingMetrics = new PricingMetrics();
        pricingMetrics.bindTo(meterRegistry);

        PricingAuditService auditService = new PricingAuditService(auditRepository, pricingRepository);
        StructuredLogger structuredLogger = new StructuredLogger();

        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        SpanBuilder spanBuilder = mock(SpanBuilder.class);
        SpanContext spanContext = mock(SpanContext.class);

        when(spanContext.getTraceId()).thenReturn("test-trace-123");
        when(span.getSpanContext()).thenReturn(spanContext);
        when(spanBuilder.setAttribute(anyString(), anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setAttribute(anyString(), anyLong())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(span);
        when(tracer.spanBuilder(anyString())).thenReturn(spanBuilder);

        PricingService pricingService = new PricingService(
            pricingRepository,
            pricingCalculator,
            pricingMetrics,
            auditService,
            structuredLogger,
            tracer
        );

        PricingController pricingController = new PricingController(pricingService);
        GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler(meterRegistry, structuredLogger);

        mockMvc = MockMvcBuilders.standaloneSetup(pricingController)
            .setControllerAdvice(exceptionHandler)
            .build();
    }

    @Test
    @DisplayName("POST /pricing should return 201 Created for valid request")
    void testCalculatePricing_ValidRequest() throws Exception {
        PricingRequestDto dto = PricingRequestDto.builder()
            .customerId("CUST001234")
            .productType("HOME_LOAN")
            .loanAmount(new BigDecimal("5000000.00"))
            .loanTenureMonths(240)
            .creditScore(780)
            .annualIncome(new BigDecimal("2400000.00"))
            .build();

        when(pricingRepository.save(any(PricingRequest.class))).thenAnswer(invocation -> {
            PricingRequest req = invocation.getArgument(0);
            if (req.getId() == null) {
                req.setId(101L);
            }
            return req;
        });

        mockMvc.perform(post("/pricing")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.request_id").value(101))
            .andExpect(jsonPath("$.customer_id").value("CUST001234"))
            .andExpect(jsonPath("$.interest_rate_pa").value(8.00));
    }

    @Test
    @DisplayName("POST /pricing should return 400 Bad Request when customer ID is invalid")
    void testCalculatePricing_InvalidCustomerId() throws Exception {
        PricingRequestDto dto = PricingRequestDto.builder()
            .customerId("INVALID_ID")
            .productType("HOME_LOAN")
            .loanAmount(new BigDecimal("5000000.00"))
            .loanTenureMonths(240)
            .build();

        mockMvc.perform(post("/pricing")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error_code").value("PRICING_VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("GET /pricing should return 200 OK with history list")
    void testGetPricingHistory() throws Exception {
        PricingRequest entity = PricingRequest.builder()
            .id(1L)
            .customerId("CUST001234")
            .productType("HOME_LOAN")
            .loanAmount(new BigDecimal("5000000"))
            .loanTenureMonths(240)
            .calculatedRate(new BigDecimal("8.00"))
            .emiAmount(new BigDecimal("41822.00"))
            .status(PricingRequest.PricingStatus.CALCULATED)
            .build();

        when(pricingRepository.findByCustomerId("CUST001234")).thenReturn(List.of(entity));

        mockMvc.perform(get("/pricing?customerId=CUST001234"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].customer_id").value("CUST001234"));
    }

    @Test
    @DisplayName("GET /pricing/42 should return 200 OK with pricing details")
    void testGetPricingById() throws Exception {
        PricingRequest entity = PricingRequest.builder()
            .id(42L)
            .customerId("CUST001234")
            .productType("HOME_LOAN")
            .loanAmount(new BigDecimal("5000000"))
            .loanTenureMonths(240)
            .calculatedRate(new BigDecimal("8.00"))
            .emiAmount(new BigDecimal("41822.00"))
            .status(PricingRequest.PricingStatus.CALCULATED)
            .build();

        when(pricingRepository.findById(42L)).thenReturn(Optional.of(entity));

        mockMvc.perform(get("/pricing/42"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.request_id").value(42));
    }

    @Test
    @DisplayName("GET /pricing/{id} should return 404 when ID does not exist")
    void testGetPricingById_NotFound() throws Exception {
        when(pricingRepository.findById(99999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/pricing/99999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error_code").value("PRICING_REQUEST_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /pricing should return 422 when credit score is below 650")
    void testCalculatePricing_LowCreditScore() throws Exception {
        // Credit score 580 is below the minimum threshold of 650
        PricingRequestDto dto = PricingRequestDto.builder()
            .customerId("CUST001234")
            .productType("HOME_LOAN")
            .loanAmount(new BigDecimal("5000000.00"))
            .loanTenureMonths(240)
            .creditScore(580)
            .annualIncome(new BigDecimal("2400000.00"))
            .build();

        // Repository must return a saved entity for the rejection path
        when(pricingRepository.save(any(PricingRequest.class))).thenAnswer(invocation -> {
            PricingRequest req = invocation.getArgument(0);
            if (req.getId() == null) req.setId(999L);
            return req;
        });

        mockMvc.perform(post("/pricing")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error_code").value("PRICING_INSUFFICIENT_CREDIT_SCORE"));
    }

    @Test
    @DisplayName("POST /pricing should return 400 when product type is unsupported")
    void testCalculatePricing_UnsupportedProductType() throws Exception {
        PricingRequestDto dto = PricingRequestDto.builder()
            .customerId("CUST001234")
            .productType("CRYPTO_LOAN")   // not a valid product type
            .loanAmount(new BigDecimal("5000000.00"))
            .loanTenureMonths(240)
            .creditScore(780)
            .annualIncome(new BigDecimal("2400000.00"))
            .build();

        when(pricingRepository.save(any(PricingRequest.class))).thenAnswer(invocation -> {
            PricingRequest req = invocation.getArgument(0);
            if (req.getId() == null) req.setId(998L);
            return req;
        });

        mockMvc.perform(post("/pricing")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error_code").value("PRICING_VALIDATION_FAILED"));
    }
}
