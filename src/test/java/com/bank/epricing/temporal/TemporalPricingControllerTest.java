package com.bank.epricing.temporal;

import com.bank.epricing.dto.PricingRequestDto;
import com.bank.epricing.dto.PricingResponseDto;
import com.bank.epricing.exception.GlobalExceptionHandler;
import com.bank.epricing.logging.StructuredLogger;
import com.bank.epricing.temporal.controller.TemporalPricingController;
import com.bank.epricing.temporal.dto.PricingWorkflowResult;
import com.bank.epricing.temporal.service.TemporalPricingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TemporalPricingControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private StubTemporalPricingService stubService;

    static class StubTemporalPricingService extends TemporalPricingService {
        PricingWorkflowResult mockResult;
        boolean approveInvoked = false;
        boolean rejectInvoked = false;
        String lastRejectedReason;

        public StubTemporalPricingService() {
            super(Optional.empty());
        }

        @Override
        public PricingWorkflowResult startPricingWorkflow(PricingRequestDto requestDto, String requestIp) {
            return mockResult;
        }

        @Override
        public void approveQuote(String workflowId) {
            this.approveInvoked = true;
        }

        @Override
        public void rejectQuote(String workflowId, String reason) {
            this.rejectInvoked = true;
            this.lastRejectedReason = reason;
        }

        @Override
        public PricingWorkflowResult getWorkflowResult(String workflowId) {
            return mockResult;
        }

        @Override
        public String getWorkflowStatus(String workflowId) {
            return mockResult != null ? mockResult.getStatus() : "UNKNOWN";
        }
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        stubService = new StubTemporalPricingService();
        TemporalPricingController controller = new TemporalPricingController(stubService);
        GlobalExceptionHandler exceptionHandler =
                new GlobalExceptionHandler(new SimpleMeterRegistry(), new StructuredLogger());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(exceptionHandler)
                .build();
    }

    @Test
    @DisplayName("POST /workflow/pricing/start returns 202 ACCEPTED with workflow details")
    void testStartWorkflowSuccess() throws Exception {
        PricingRequestDto request = new PricingRequestDto();
        request.setCustomerId("CUST001234");
        request.setProductType("HOME_LOAN");
        request.setLoanAmount(new BigDecimal("5000000.00"));
        request.setLoanTenureMonths(240);
        request.setCreditScore(780);
        request.setAnnualIncome(new BigDecimal("1800000.00"));

        PricingResponseDto quote = new PricingResponseDto();
        quote.setRequestId(101L);
        quote.setCustomerId("CUST001234");
        quote.setInterestRatePA(new BigDecimal("8.50"));

        PricingWorkflowResult result = new PricingWorkflowResult(
            "pricing-CUST001234-abcd1234",
            "CUST001234",
            "HOME_LOAN",
            "QUOTED",
            quote
        );
        stubService.mockResult = result;

        mockMvc.perform(post("/workflow/pricing/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.workflow_id").value("pricing-CUST001234-abcd1234"))
            .andExpect(jsonPath("$.status").value("QUOTED"));
    }

    @Test
    @DisplayName("POST /workflow/pricing/{workflowId}/approve sends approval signal")
    void testApproveWorkflow() throws Exception {
        mockMvc.perform(post("/workflow/pricing/wf-123/approve"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"));

        assertTrue(stubService.approveInvoked);
    }

    @Test
    @DisplayName("POST /workflow/pricing/{workflowId}/reject sends rejection signal")
    void testRejectWorkflow() throws Exception {
        mockMvc.perform(post("/workflow/pricing/wf-123/reject")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("reason", "Found better rate"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REJECTED"))
            .andExpect(jsonPath("$.reason").value("Found better rate"));

        assertTrue(stubService.rejectInvoked);
        assertEquals("Found better rate", stubService.lastRejectedReason);
    }

    @Test
    @DisplayName("GET /workflow/pricing/{workflowId} returns workflow state snapshot")
    void testGetWorkflowStatus() throws Exception {
        PricingWorkflowResult result = new PricingWorkflowResult(
            "wf-123",
            "CUST001234",
            "HOME_LOAN",
            "APPROVED",
            null
        );
        stubService.mockResult = result;

        mockMvc.perform(get("/workflow/pricing/wf-123"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.workflow_id").value("wf-123"))
            .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    @DisplayName("GET /workflow/pricing/{workflowId}/status returns quick workflow status string")
    void testGetQuickWorkflowStatus() throws Exception {
        PricingWorkflowResult result = new PricingWorkflowResult(
            "wf-123",
            "CUST001234",
            "HOME_LOAN",
            "QUOTED",
            null
        );
        stubService.mockResult = result;

        mockMvc.perform(get("/workflow/pricing/wf-123/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.workflow_id").value("wf-123"))
            .andExpect(jsonPath("$.status").value("QUOTED"));
    }

    @Test
    @DisplayName("R3: POST /workflow/pricing/start accepts enterprise hyphenated customer ID CUST-LIVE-001")
    void testStartWorkflowAcceptsHyphenatedCustomerId() throws Exception {
        PricingRequestDto request = PricingRequestDto.builder()
                .customerId("CUST-LIVE-001")
                .productType("HOME_LOAN")
                .loanAmount(new BigDecimal("5000000.00"))
                .loanTenureMonths(240)
                .creditScore(780)
                .annualIncome(new BigDecimal("1800000.00"))
                .build();

        PricingResponseDto quote = new PricingResponseDto();
        quote.setRequestId(102L);
        quote.setCustomerId("CUST-LIVE-001");
        quote.setInterestRatePA(new BigDecimal("8.50"));

        stubService.mockResult = new PricingWorkflowResult(
                "pricing-CUST-LIVE-001-abcd1234",
                "CUST-LIVE-001",
                "HOME_LOAN",
                "QUOTED",
                quote
        );

        mockMvc.perform(post("/workflow/pricing/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.customer_id").value("CUST-LIVE-001"))
                .andExpect(jsonPath("$.status").value("QUOTED"));
    }

    @Test
    @DisplayName("R3: POST /workflow/pricing/start rejects invalid customer IDs with HTTP 400")
    void testStartWorkflowRejectsInvalidCustomerIds() throws Exception {
        for (String invalidId : List.of("INVALID_ID", "USER000001", "cust000001", "CUST------", "CUST_LIVE_001")) {
            PricingRequestDto request = PricingRequestDto.builder()
                    .customerId(invalidId)
                    .productType("HOME_LOAN")
                    .loanAmount(new BigDecimal("5000000.00"))
                    .loanTenureMonths(240)
                    .creditScore(780)
                    .annualIncome(new BigDecimal("1800000.00"))
                    .build();

            mockMvc.perform(post("/workflow/pricing/start")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error_code").value("PRICING_VALIDATION_FAILED"));
        }
    }
}
