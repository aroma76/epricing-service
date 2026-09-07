package com.bank.epricing.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * PricingResponseDto — Output Data Transfer Object for pricing calculation results.
 */
public class PricingResponseDto {

    @JsonProperty("request_id")
    private Long requestId;

    @JsonProperty("customer_id")
    private String customerId;

    @JsonProperty("product_type")
    private String productType;

    @JsonProperty("loan_amount")
    private BigDecimal loanAmount;

    @JsonProperty("loan_tenure_months")
    private Integer loanTenureMonths;

    @JsonProperty("interest_rate_pa")
    private BigDecimal interestRatePA;

    @JsonProperty("emi_amount")
    private BigDecimal emiAmount;

    @JsonProperty("total_payable_amount")
    private BigDecimal totalPayableAmount;

    @JsonProperty("total_interest_payable")
    private BigDecimal totalInterestPayable;

    @JsonProperty("status")
    private String status;

    @JsonProperty("message")
    private String message;

    @JsonProperty("trace_id")
    private String traceId;

    @JsonProperty("processing_time_ms")
    private Long processingTimeMs;

    @JsonProperty("calculated_at")
    private LocalDateTime calculatedAt;

    @JsonProperty("risk_category")
    private String riskCategory;

    @JsonProperty("rate_valid_until")
    private LocalDateTime rateValidUntil;

    public PricingResponseDto() {
    }

    public PricingResponseDto(Long requestId, String customerId, String productType, BigDecimal loanAmount,
                              Integer loanTenureMonths, BigDecimal interestRatePA, BigDecimal emiAmount,
                              BigDecimal totalPayableAmount, BigDecimal totalInterestPayable, String status,
                              String message, String traceId, Long processingTimeMs, LocalDateTime calculatedAt,
                              String riskCategory, LocalDateTime rateValidUntil) {
        this.requestId = requestId;
        this.customerId = customerId;
        this.productType = productType;
        this.loanAmount = loanAmount;
        this.loanTenureMonths = loanTenureMonths;
        this.interestRatePA = interestRatePA;
        this.emiAmount = emiAmount;
        this.totalPayableAmount = totalPayableAmount;
        this.totalInterestPayable = totalInterestPayable;
        this.status = status;
        this.message = message;
        this.traceId = traceId;
        this.processingTimeMs = processingTimeMs;
        this.calculatedAt = calculatedAt;
        this.riskCategory = riskCategory;
        this.rateValidUntil = rateValidUntil;
    }

    // Getters and Setters
    public Long getRequestId() { return requestId; }
    public void setRequestId(Long requestId) { this.requestId = requestId; }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public String getProductType() { return productType; }
    public void setProductType(String productType) { this.productType = productType; }

    public BigDecimal getLoanAmount() { return loanAmount; }
    public void setLoanAmount(BigDecimal loanAmount) { this.loanAmount = loanAmount; }

    public Integer getLoanTenureMonths() { return loanTenureMonths; }
    public void setLoanTenureMonths(Integer loanTenureMonths) { this.loanTenureMonths = loanTenureMonths; }

    public BigDecimal getInterestRatePA() { return interestRatePA; }
    public void setInterestRatePA(BigDecimal interestRatePA) { this.interestRatePA = interestRatePA; }

    public BigDecimal getEmiAmount() { return emiAmount; }
    public void setEmiAmount(BigDecimal emiAmount) { this.emiAmount = emiAmount; }

    public BigDecimal getTotalPayableAmount() { return totalPayableAmount; }
    public void setTotalPayableAmount(BigDecimal totalPayableAmount) { this.totalPayableAmount = totalPayableAmount; }

    public BigDecimal getTotalInterestPayable() { return totalInterestPayable; }
    public void setTotalInterestPayable(BigDecimal totalInterestPayable) { this.totalInterestPayable = totalInterestPayable; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public Long getProcessingTimeMs() { return processingTimeMs; }
    public void setProcessingTimeMs(Long processingTimeMs) { this.processingTimeMs = processingTimeMs; }

    public LocalDateTime getCalculatedAt() { return calculatedAt; }
    public void setCalculatedAt(LocalDateTime calculatedAt) { this.calculatedAt = calculatedAt; }

    public String getRiskCategory() { return riskCategory; }
    public void setRiskCategory(String riskCategory) { this.riskCategory = riskCategory; }

    public LocalDateTime getRateValidUntil() { return rateValidUntil; }
    public void setRateValidUntil(LocalDateTime rateValidUntil) { this.rateValidUntil = rateValidUntil; }

    public static PricingResponseDtoBuilder builder() {
        return new PricingResponseDtoBuilder();
    }

    public static class PricingResponseDtoBuilder {
        private Long requestId;
        private String customerId;
        private String productType;
        private BigDecimal loanAmount;
        private Integer loanTenureMonths;
        private BigDecimal interestRatePA;
        private BigDecimal emiAmount;
        private BigDecimal totalPayableAmount;
        private BigDecimal totalInterestPayable;
        private String status;
        private String message;
        private String traceId;
        private Long processingTimeMs;
        private LocalDateTime calculatedAt;
        private String riskCategory;
        private LocalDateTime rateValidUntil;

        PricingResponseDtoBuilder() {}

        public PricingResponseDtoBuilder requestId(Long requestId) { this.requestId = requestId; return this; }
        public PricingResponseDtoBuilder customerId(String customerId) { this.customerId = customerId; return this; }
        public PricingResponseDtoBuilder productType(String productType) { this.productType = productType; return this; }
        public PricingResponseDtoBuilder loanAmount(BigDecimal loanAmount) { this.loanAmount = loanAmount; return this; }
        public PricingResponseDtoBuilder loanTenureMonths(Integer loanTenureMonths) { this.loanTenureMonths = loanTenureMonths; return this; }
        public PricingResponseDtoBuilder interestRatePA(BigDecimal interestRatePA) { this.interestRatePA = interestRatePA; return this; }
        public PricingResponseDtoBuilder emiAmount(BigDecimal emiAmount) { this.emiAmount = emiAmount; return this; }
        public PricingResponseDtoBuilder totalPayableAmount(BigDecimal totalPayableAmount) { this.totalPayableAmount = totalPayableAmount; return this; }
        public PricingResponseDtoBuilder totalInterestPayable(BigDecimal totalInterestPayable) { this.totalInterestPayable = totalInterestPayable; return this; }
        public PricingResponseDtoBuilder status(String status) { this.status = status; return this; }
        public PricingResponseDtoBuilder message(String message) { this.message = message; return this; }
        public PricingResponseDtoBuilder traceId(String traceId) { this.traceId = traceId; return this; }
        public PricingResponseDtoBuilder processingTimeMs(Long processingTimeMs) { this.processingTimeMs = processingTimeMs; return this; }
        public PricingResponseDtoBuilder calculatedAt(LocalDateTime calculatedAt) { this.calculatedAt = calculatedAt; return this; }
        public PricingResponseDtoBuilder riskCategory(String riskCategory) { this.riskCategory = riskCategory; return this; }
        public PricingResponseDtoBuilder rateValidUntil(LocalDateTime rateValidUntil) { this.rateValidUntil = rateValidUntil; return this; }

        public PricingResponseDto build() {
            return new PricingResponseDto(requestId, customerId, productType, loanAmount, loanTenureMonths,
                    interestRatePA, emiAmount, totalPayableAmount, totalInterestPayable, status, message,
                    traceId, processingTimeMs, calculatedAt, riskCategory, rateValidUntil);
        }
    }
}
