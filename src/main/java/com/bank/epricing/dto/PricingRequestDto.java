package com.bank.epricing.dto;

import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * PricingRequestDto — Input Data Transfer Object for pricing calculation requests.
 */
public class PricingRequestDto {

    @NotBlank(message = "Customer ID is required")
    @Pattern(
        regexp = "^CUST(?=[A-Z0-9-]*[A-Z0-9])[A-Z0-9-]{6,46}$",
        message = "Customer ID must start with CUST followed by 6-46 uppercase alphanumeric characters or hyphens (total 10-50 chars)"
    )
    @JsonProperty("customer_id")
    private String customerId;

    @NotBlank(message = "Product type is required")
    @Pattern(
        regexp = "^(HOME_LOAN|PERSONAL_LOAN|BUSINESS_LOAN|AUTO_LOAN|EDUCATION_LOAN)$",
        message = "Product type must be one of: HOME_LOAN, PERSONAL_LOAN, BUSINESS_LOAN, AUTO_LOAN, EDUCATION_LOAN"
    )
    @JsonProperty("product_type")
    private String productType;

    @NotNull(message = "Loan amount is required")
    @DecimalMin(value = "10000.00", message = "Minimum loan amount is ₹10,000")
    @DecimalMax(value = "100000000.00", message = "Maximum loan amount is ₹10 crore")
    @JsonProperty("loan_amount")
    private BigDecimal loanAmount;

    @NotNull(message = "Loan tenure is required")
    @Min(value = 12, message = "Minimum tenure is 12 months")
    @Max(value = 360, message = "Maximum tenure is 360 months")
    @JsonProperty("loan_tenure_months")
    private Integer loanTenureMonths;

    @NotNull(message = "Credit score is required")
    @Min(value = 300, message = "Credit score minimum is 300")
    @Max(value = 900, message = "Credit score maximum is 900")
    @JsonProperty("credit_score")
    private Integer creditScore;

    @NotNull(message = "Annual income is required for eligibility assessment")
    @DecimalMin(value = "100000.00", message = "Minimum annual income is ₹1,00,000")
    @DecimalMax(value = "9999999999999.99", message = "Annual income exceeds maximum supported value")
    @JsonProperty("annual_income")
    private BigDecimal annualIncome;

    @Size(max = 200, message = "Purpose cannot exceed 200 characters")
    @JsonProperty("loan_purpose")
    private String loanPurpose;

    @Size(max = 128, message = "Idempotency key cannot exceed 128 characters")
    @JsonProperty("idempotency_key")
    private String idempotencyKey;

    public PricingRequestDto() {
    }

    public PricingRequestDto(String customerId, String productType, BigDecimal loanAmount,
                             Integer loanTenureMonths, Integer creditScore, BigDecimal annualIncome, String loanPurpose) {
        this.customerId = customerId;
        this.productType = productType;
        this.loanAmount = loanAmount;
        this.loanTenureMonths = loanTenureMonths;
        this.creditScore = creditScore;
        this.annualIncome = annualIncome;
        this.loanPurpose = loanPurpose;
    }

    // Getters and Setters
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public String getProductType() { return productType; }
    public void setProductType(String productType) { this.productType = productType; }

    public BigDecimal getLoanAmount() { return loanAmount; }
    public void setLoanAmount(BigDecimal loanAmount) { this.loanAmount = loanAmount; }

    public Integer getLoanTenureMonths() { return loanTenureMonths; }
    public void setLoanTenureMonths(Integer loanTenureMonths) { this.loanTenureMonths = loanTenureMonths; }

    public Integer getCreditScore() { return creditScore; }
    public void setCreditScore(Integer creditScore) { this.creditScore = creditScore; }

    public BigDecimal getAnnualIncome() { return annualIncome; }
    public void setAnnualIncome(BigDecimal annualIncome) { this.annualIncome = annualIncome; }

    public String getLoanPurpose() { return loanPurpose; }
    public void setLoanPurpose(String loanPurpose) { this.loanPurpose = loanPurpose; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public static PricingRequestDtoBuilder builder() {
        return new PricingRequestDtoBuilder();
    }

    public static class PricingRequestDtoBuilder {
        private String customerId;
        private String productType;
        private BigDecimal loanAmount;
        private Integer loanTenureMonths;
        private Integer creditScore;
        private BigDecimal annualIncome;
        private String loanPurpose;
        private String idempotencyKey;

        PricingRequestDtoBuilder() {}

        public PricingRequestDtoBuilder customerId(String customerId) { this.customerId = customerId; return this; }
        public PricingRequestDtoBuilder productType(String productType) { this.productType = productType; return this; }
        public PricingRequestDtoBuilder loanAmount(BigDecimal loanAmount) { this.loanAmount = loanAmount; return this; }
        public PricingRequestDtoBuilder loanTenureMonths(Integer loanTenureMonths) { this.loanTenureMonths = loanTenureMonths; return this; }
        public PricingRequestDtoBuilder creditScore(Integer creditScore) { this.creditScore = creditScore; return this; }
        public PricingRequestDtoBuilder annualIncome(BigDecimal annualIncome) { this.annualIncome = annualIncome; return this; }
        public PricingRequestDtoBuilder loanPurpose(String loanPurpose) { this.loanPurpose = loanPurpose; return this; }
        public PricingRequestDtoBuilder idempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; return this; }

        public PricingRequestDto build() {
            PricingRequestDto dto = new PricingRequestDto(customerId, productType, loanAmount, loanTenureMonths, creditScore, annualIncome, loanPurpose);
            dto.setIdempotencyKey(idempotencyKey);
            return dto;
        }
    }
}
