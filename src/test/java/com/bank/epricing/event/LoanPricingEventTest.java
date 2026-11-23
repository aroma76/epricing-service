package com.bank.epricing.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class LoanPricingEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("Should serialize and deserialize LoanPricingEvent correctly via Jackson with snake_case")
    void testSerializationDeserialization() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        LoanPricingEvent event = LoanPricingEvent.builder()
                .requestId(101L)
                .customerId("CUST001234")
                .productType("HOME_LOAN")
                .loanAmount(new BigDecimal("5000000.00"))
                .interestRate(new BigDecimal("8.25"))
                .emiAmount(new BigDecimal("42567.89"))
                .loanTenureMonths(240)
                .totalPayableAmount(new BigDecimal("10216293.60"))
                .totalInterestPayable(new BigDecimal("5216293.60"))
                .riskCategory("LOW")
                .status("CALCULATED")
                .timestamp(now)
                .traceId("trace-abc-123")
                .build();

        String json = objectMapper.writeValueAsString(event);
        assertNotNull(json);
        assertTrue(json.contains("\"request_id\":101"));
        assertTrue(json.contains("\"customer_id\":\"CUST001234\""));
        assertTrue(json.contains("\"product_type\":\"HOME_LOAN\""));
        assertTrue(json.contains("\"interest_rate\":8.25"));
        assertTrue(json.contains("\"trace_id\":\"trace-abc-123\""));

        LoanPricingEvent deserialized = objectMapper.readValue(json, LoanPricingEvent.class);
        assertEquals(101L, deserialized.getRequestId());
        assertEquals("CUST001234", deserialized.getCustomerId());
        assertEquals("HOME_LOAN", deserialized.getProductType());
        assertEquals(new BigDecimal("5000000.00"), deserialized.getLoanAmount());
        assertEquals(new BigDecimal("8.25"), deserialized.getInterestRate());
        assertEquals(new BigDecimal("42567.89"), deserialized.getEmiAmount());
        assertEquals(240, deserialized.getLoanTenureMonths());
        assertEquals("LOW", deserialized.getRiskCategory());
        assertEquals("CALCULATED", deserialized.getStatus());
        assertEquals("trace-abc-123", deserialized.getTraceId());
    }

    @Test
    @DisplayName("Should deserialize from camelCase and alternate alias JSON payload")
    void testCamelCaseAndAliasDeserialization() throws Exception {
        String camelCaseJson = """
            {
                "requestId": 303,
                "customerId": "CUST005678",
                "productType": "PERSONAL_LOAN",
                "loanAmount": 250000.00,
                "interestRatePA": 12.50,
                "emiAmount": 8370.25,
                "loanTenureMonths": 36,
                "totalPayableAmount": 301329.00,
                "totalInterestPayable": 51329.00,
                "riskCategory": "HIGH",
                "pricingStatus": "CALCULATED",
                "createdAt": "2026-11-18T10:15:30.000",
                "traceId": "trace-camel-case-456"
            }
            """;

        LoanPricingEvent deserialized = objectMapper.readValue(camelCaseJson, LoanPricingEvent.class);
        assertEquals(303L, deserialized.getRequestId());
        assertEquals("CUST005678", deserialized.getCustomerId());
        assertEquals("PERSONAL_LOAN", deserialized.getProductType());
        assertEquals(new BigDecimal("250000.00"), deserialized.getLoanAmount());
        assertEquals(new BigDecimal("12.50"), deserialized.getInterestRate());
        assertEquals(new BigDecimal("8370.25"), deserialized.getEmiAmount());
        assertEquals(36, deserialized.getLoanTenureMonths());
        assertEquals("HIGH", deserialized.getRiskCategory());
        assertEquals("CALCULATED", deserialized.getStatus());
        assertEquals("trace-camel-case-456", deserialized.getTraceId());
        assertNotNull(deserialized.getTimestamp());
    }

    @Test
    @DisplayName("Should populate all fields via Builder and handle toString")
    void testBuilderAndToString() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 18, 12, 0, 0);
        LoanPricingEvent event = LoanPricingEvent.builder()
                .requestId(202L)
                .customerId("CUST009999")
                .productType("AUTO_LOAN")
                .loanAmount(new BigDecimal("1500000.00"))
                .interestRate(new BigDecimal("9.50"))
                .emiAmount(new BigDecimal("31500.00"))
                .loanTenureMonths(60)
                .totalPayableAmount(new BigDecimal("1890000.00"))
                .totalInterestPayable(new BigDecimal("390000.00"))
                .riskCategory("MEDIUM")
                .status("CALCULATED")
                .timestamp(now)
                .traceId("trace-xyz-789")
                .build();

        assertEquals(202L, event.getRequestId());
        assertEquals("CUST009999", event.getCustomerId());
        assertEquals("AUTO_LOAN", event.getProductType());
        assertEquals(new BigDecimal("1500000.00"), event.getLoanAmount());
        assertEquals(new BigDecimal("9.50"), event.getInterestRate());
        assertEquals(new BigDecimal("31500.00"), event.getEmiAmount());
        assertEquals(60, event.getLoanTenureMonths());
        assertEquals(new BigDecimal("1890000.00"), event.getTotalPayableAmount());
        assertEquals(new BigDecimal("390000.00"), event.getTotalInterestPayable());
        assertEquals("MEDIUM", event.getRiskCategory());
        assertEquals("CALCULATED", event.getStatus());
        assertEquals(now.atZone(java.time.ZoneId.of("UTC")).toInstant(), event.getTimestamp());
        assertEquals("trace-xyz-789", event.getTraceId());

        String str = event.toString();
        assertTrue(str.contains("requestId=202"));
        assertTrue(str.contains("customerId='CUST009999'"));
    }
}
