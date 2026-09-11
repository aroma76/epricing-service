package com.bank.epricing.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * PricingRequest — JPA Entity representing a pricing calculation request in the database.
 */
@Entity
@Table(
    name = "pricing_requests",
    indexes = {
        @Index(name = "idx_customer_id", columnList = "customer_id"),
        @Index(name = "idx_product_type", columnList = "product_type"),
        @Index(name = "idx_status", columnList = "status"),
        @Index(name = "idx_created_at", columnList = "created_at")
    }
)
public class PricingRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, length = 50, updatable = false)
    @NotBlank(message = "Customer ID cannot be blank")
    private String customerId;

    @Column(name = "product_type", nullable = false, length = 50)
    @NotBlank(message = "Product type cannot be blank")
    private String productType;

    @Column(name = "loan_amount", nullable = false, precision = 15, scale = 2)
    @NotNull(message = "Loan amount cannot be null")
    @DecimalMin(value = "1000.00", message = "Minimum loan amount is ₹1,000")
    @DecimalMax(value = "100000000.00", message = "Maximum loan amount is ₹10 crore")
    private BigDecimal loanAmount;

    @Column(name = "loan_tenure_months", nullable = false)
    @NotNull
    @Min(value = 12, message = "Minimum tenure is 12 months")
    @Max(value = 360, message = "Maximum tenure is 360 months (30 years)")
    private Integer loanTenureMonths;

    @Column(name = "credit_score")
    @Min(value = 300, message = "Minimum credit score is 300")
    @Max(value = 900, message = "Maximum credit score is 900")
    private Integer creditScore;

    @Column(name = "annual_income", precision = 15, scale = 2)
    private BigDecimal annualIncome;

    @Column(name = "loan_purpose", length = 200)
    @Size(max = 200, message = "Loan purpose cannot exceed 200 characters")
    private String loanPurpose;

    @Column(name = "total_payable_amount", precision = 15, scale = 2)
    private BigDecimal totalPayableAmount;

    @Column(name = "total_interest_payable", precision = 15, scale = 2)
    private BigDecimal totalInterestPayable;

    @Column(name = "risk_category", length = 30)
    private String riskCategory;

    @Column(name = "calculated_rate", precision = 5, scale = 2)
    private BigDecimal calculatedRate;

    @Column(name = "emi_amount", precision = 15, scale = 2)
    private BigDecimal emiAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PricingStatus status;

    @Column(name = "processing_time_ms")
    private Long processingTimeMs;

    @Column(name = "request_ip", length = 50)
    private String requestIp;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Lifecycle states of a pricing calculation.
     *
     * CALCULATED — pricing engine ran successfully; rate and EMI computed.
     * REJECTED   — customer failed eligibility (credit score, FOIR). No rate issued.
     * ERROR      — technical failure during processing. Record saved for audit/ops visibility.
     *
     * NOTE: PENDING (pre-processing) and APPROVED (post-underwriting) are out of scope
     * for this service. This service is a pricing engine, not a full loan origination system.
     * Approval workflow would live in a separate LOS (Loan Origination System).
     */
    public enum PricingStatus {
        CALCULATED,
        REJECTED,
        ERROR
    }

    public PricingRequest() {
    }

    public PricingRequest(Long id, String customerId, String productType, BigDecimal loanAmount,
                          Integer loanTenureMonths, Integer creditScore, BigDecimal annualIncome, String loanPurpose,
                          BigDecimal totalPayableAmount, BigDecimal totalInterestPayable, String riskCategory,
                          BigDecimal calculatedRate, BigDecimal emiAmount, PricingStatus status,
                          Long processingTimeMs, String requestIp, String traceId, String errorMessage,
                          LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.customerId = customerId;
        this.productType = productType;
        this.loanAmount = loanAmount;
        this.loanTenureMonths = loanTenureMonths;
        this.creditScore = creditScore;
        this.annualIncome = annualIncome;
        this.loanPurpose = loanPurpose;
        this.totalPayableAmount = totalPayableAmount;
        this.totalInterestPayable = totalInterestPayable;
        this.riskCategory = riskCategory;
        this.calculatedRate = calculatedRate;
        this.emiAmount = emiAmount;
        this.status = status;
        this.processingTimeMs = processingTimeMs;
        this.requestIp = requestIp;
        this.traceId = traceId;
        this.errorMessage = errorMessage;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

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

    public BigDecimal getTotalPayableAmount() { return totalPayableAmount; }
    public void setTotalPayableAmount(BigDecimal totalPayableAmount) { this.totalPayableAmount = totalPayableAmount; }

    public BigDecimal getTotalInterestPayable() { return totalInterestPayable; }
    public void setTotalInterestPayable(BigDecimal totalInterestPayable) { this.totalInterestPayable = totalInterestPayable; }

    public String getRiskCategory() { return riskCategory; }
    public void setRiskCategory(String riskCategory) { this.riskCategory = riskCategory; }

    public BigDecimal getCalculatedRate() { return calculatedRate; }
    public void setCalculatedRate(BigDecimal calculatedRate) { this.calculatedRate = calculatedRate; }

    public BigDecimal getEmiAmount() { return emiAmount; }
    public void setEmiAmount(BigDecimal emiAmount) { this.emiAmount = emiAmount; }

    public PricingStatus getStatus() { return status; }
    public void setStatus(PricingStatus status) { this.status = status; }

    public Long getProcessingTimeMs() { return processingTimeMs; }
    public void setProcessingTimeMs(Long processingTimeMs) { this.processingTimeMs = processingTimeMs; }

    public String getRequestIp() { return requestIp; }
    public void setRequestIp(String requestIp) { this.requestIp = requestIp; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    // Builder pattern implementation
    public static PricingRequestBuilder builder() {
        return new PricingRequestBuilder();
    }

    public static class PricingRequestBuilder {
        private Long id;
        private String customerId;
        private String productType;
        private BigDecimal loanAmount;
        private Integer loanTenureMonths;
        private Integer creditScore;
        private BigDecimal annualIncome;
        private String loanPurpose;
        private BigDecimal totalPayableAmount;
        private BigDecimal totalInterestPayable;
        private String riskCategory;
        private BigDecimal calculatedRate;
        private BigDecimal emiAmount;
        private PricingStatus status;
        private Long processingTimeMs;
        private String requestIp;
        private String traceId;
        private String errorMessage;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        PricingRequestBuilder() {}

        public PricingRequestBuilder id(Long id) { this.id = id; return this; }
        public PricingRequestBuilder customerId(String customerId) { this.customerId = customerId; return this; }
        public PricingRequestBuilder productType(String productType) { this.productType = productType; return this; }
        public PricingRequestBuilder loanAmount(BigDecimal loanAmount) { this.loanAmount = loanAmount; return this; }
        public PricingRequestBuilder loanTenureMonths(Integer loanTenureMonths) { this.loanTenureMonths = loanTenureMonths; return this; }
        public PricingRequestBuilder creditScore(Integer creditScore) { this.creditScore = creditScore; return this; }
        public PricingRequestBuilder annualIncome(BigDecimal annualIncome) { this.annualIncome = annualIncome; return this; }
        public PricingRequestBuilder loanPurpose(String loanPurpose) { this.loanPurpose = loanPurpose; return this; }
        public PricingRequestBuilder totalPayableAmount(BigDecimal totalPayableAmount) { this.totalPayableAmount = totalPayableAmount; return this; }
        public PricingRequestBuilder totalInterestPayable(BigDecimal totalInterestPayable) { this.totalInterestPayable = totalInterestPayable; return this; }
        public PricingRequestBuilder riskCategory(String riskCategory) { this.riskCategory = riskCategory; return this; }
        public PricingRequestBuilder calculatedRate(BigDecimal calculatedRate) { this.calculatedRate = calculatedRate; return this; }
        public PricingRequestBuilder emiAmount(BigDecimal emiAmount) { this.emiAmount = emiAmount; return this; }
        public PricingRequestBuilder status(PricingStatus status) { this.status = status; return this; }
        public PricingRequestBuilder processingTimeMs(Long processingTimeMs) { this.processingTimeMs = processingTimeMs; return this; }
        public PricingRequestBuilder requestIp(String requestIp) { this.requestIp = requestIp; return this; }
        public PricingRequestBuilder traceId(String traceId) { this.traceId = traceId; return this; }
        public PricingRequestBuilder errorMessage(String errorMessage) { this.errorMessage = errorMessage; return this; }
        public PricingRequestBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public PricingRequestBuilder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }

        public PricingRequest build() {
            return new PricingRequest(id, customerId, productType, loanAmount, loanTenureMonths, creditScore,
                    annualIncome, loanPurpose, totalPayableAmount, totalInterestPayable, riskCategory,
                    calculatedRate, emiAmount, status, processingTimeMs, requestIp, traceId, errorMessage,
                    createdAt, updatedAt);
        }
    }
}
