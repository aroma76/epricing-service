package com.bank.epricing.event;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Domain Event representing a completed loan pricing calculation.
 * Published to Apache Kafka topic (e.g. loan-pricing-events) for downstream
 * consumers such as analytics, risk audit, and notification pipelines.
 *
 * ISSUE-08 FIX: Uses Instant (UTC epoch-based) instead of LocalDateTime (timezone-less).
 * @JsonFormat with timezone=UTC ensures the serialized string always carries
 * the "Z" suffix (ISO-8601 with offset), eliminating the 5.5-hour IST/UTC skew.
 * LenientInstantDeserializer supports parsing both standard ISO Instants and legacy
 * LocalDateTime strings without timezone offsets.
 */
public class LoanPricingEvent {

    @JsonProperty("request_id")
    @JsonAlias({"requestId"})
    private Long requestId;

    @JsonProperty("customer_id")
    @JsonAlias({"customerId"})
    private String customerId;

    @JsonProperty("product_type")
    @JsonAlias({"productType"})
    private String productType;

    @JsonProperty("loan_amount")
    @JsonAlias({"loanAmount"})
    private BigDecimal loanAmount;

    @JsonProperty("interest_rate")
    @JsonAlias({"interestRate", "interest_rate_pa", "interestRatePA"})
    private BigDecimal interestRate;

    @JsonProperty("emi_amount")
    @JsonAlias({"emiAmount"})
    private BigDecimal emiAmount;

    @JsonProperty("loan_tenure_months")
    @JsonAlias({"loanTenureMonths"})
    private Integer loanTenureMonths;

    @JsonProperty("total_payable_amount")
    @JsonAlias({"totalPayableAmount"})
    private BigDecimal totalPayableAmount;

    @JsonProperty("total_interest_payable")
    @JsonAlias({"totalInterestPayable"})
    private BigDecimal totalInterestPayable;

    @JsonProperty("risk_category")
    @JsonAlias({"riskCategory"})
    private String riskCategory;

    @JsonProperty("status")
    @JsonAlias({"pricingStatus"})
    private String status;

    @JsonProperty("timestamp")
    @JsonAlias({"createdAt", "calculatedAt"})
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
    @JsonDeserialize(using = LenientInstantDeserializer.class)
    private Instant timestamp;

    @JsonProperty("trace_id")
    @JsonAlias({"traceId"})
    private String traceId;

    public static class LenientInstantDeserializer extends JsonDeserializer<Instant> {
        @Override
        public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String text = p.getText();
            if (text == null || text.isBlank()) {
                return null;
            }
            try {
                return Instant.parse(text);
            } catch (Exception e) {
                try {
                    LocalDateTime ldt = LocalDateTime.parse(text);
                    return ldt.atZone(ZoneId.of("UTC")).toInstant();
                } catch (Exception ex) {
                    try {
                        return Instant.ofEpochMilli(Long.parseLong(text));
                    } catch (Exception ex2) {
                        return null;
                    }
                }
            }
        }
    }

    public LoanPricingEvent() {
    }

    public LoanPricingEvent(Long requestId, String customerId, String productType,
                            BigDecimal loanAmount, BigDecimal interestRate, BigDecimal emiAmount,
                            Integer loanTenureMonths, BigDecimal totalPayableAmount,
                            BigDecimal totalInterestPayable, String riskCategory,
                            String status, Instant timestamp, String traceId) {
        this.requestId = requestId;
        this.customerId = customerId;
        this.productType = productType;
        this.loanAmount = loanAmount;
        this.interestRate = interestRate;
        this.emiAmount = emiAmount;
        this.loanTenureMonths = loanTenureMonths;
        this.totalPayableAmount = totalPayableAmount;
        this.totalInterestPayable = totalInterestPayable;
        this.riskCategory = riskCategory;
        this.status = status;
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.traceId = traceId;
    }

    public LoanPricingEvent(Long requestId, String customerId, String productType,
                            BigDecimal loanAmount, BigDecimal interestRate, BigDecimal emiAmount,
                            Integer loanTenureMonths, BigDecimal totalPayableAmount,
                            BigDecimal totalInterestPayable, String riskCategory,
                            String status, LocalDateTime timestamp, String traceId) {
        this(requestId, customerId, productType, loanAmount, interestRate, emiAmount,
             loanTenureMonths, totalPayableAmount, totalInterestPayable, riskCategory,
             status, timestamp != null ? timestamp.atZone(ZoneId.of("UTC")).toInstant() : Instant.now(), traceId);
    }

    public Long getRequestId() {
        return requestId;
    }

    public void setRequestId(Long requestId) {
        this.requestId = requestId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getProductType() {
        return productType;
    }

    public void setProductType(String productType) {
        this.productType = productType;
    }

    public BigDecimal getLoanAmount() {
        return loanAmount;
    }

    public void setLoanAmount(BigDecimal loanAmount) {
        this.loanAmount = loanAmount;
    }

    public BigDecimal getInterestRate() {
        return interestRate;
    }

    public void setInterestRate(BigDecimal interestRate) {
        this.interestRate = interestRate;
    }

    public BigDecimal getEmiAmount() {
        return emiAmount;
    }

    public void setEmiAmount(BigDecimal emiAmount) {
        this.emiAmount = emiAmount;
    }

    public Integer getLoanTenureMonths() {
        return loanTenureMonths;
    }

    public void setLoanTenureMonths(Integer loanTenureMonths) {
        this.loanTenureMonths = loanTenureMonths;
    }

    public BigDecimal getTotalPayableAmount() {
        return totalPayableAmount;
    }

    public void setTotalPayableAmount(BigDecimal totalPayableAmount) {
        this.totalPayableAmount = totalPayableAmount;
    }

    public BigDecimal getTotalInterestPayable() {
        return totalInterestPayable;
    }

    public void setTotalInterestPayable(BigDecimal totalInterestPayable) {
        this.totalInterestPayable = totalInterestPayable;
    }

    public String getRiskCategory() {
        return riskCategory;
    }

    public void setRiskCategory(String riskCategory) {
        this.riskCategory = riskCategory;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long requestId;
        private String customerId;
        private String productType;
        private BigDecimal loanAmount;
        private BigDecimal interestRate;
        private BigDecimal emiAmount;
        private Integer loanTenureMonths;
        private BigDecimal totalPayableAmount;
        private BigDecimal totalInterestPayable;
        private String riskCategory;
        private String status;
        private Instant timestamp;
        private String traceId;

        public Builder requestId(Long requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder customerId(String customerId) {
            this.customerId = customerId;
            return this;
        }

        public Builder productType(String productType) {
            this.productType = productType;
            return this;
        }

        public Builder loanAmount(BigDecimal loanAmount) {
            this.loanAmount = loanAmount;
            return this;
        }

        public Builder interestRate(BigDecimal interestRate) {
            this.interestRate = interestRate;
            return this;
        }

        public Builder emiAmount(BigDecimal emiAmount) {
            this.emiAmount = emiAmount;
            return this;
        }

        public Builder loanTenureMonths(Integer loanTenureMonths) {
            this.loanTenureMonths = loanTenureMonths;
            return this;
        }

        public Builder totalPayableAmount(BigDecimal totalPayableAmount) {
            this.totalPayableAmount = totalPayableAmount;
            return this;
        }

        public Builder totalInterestPayable(BigDecimal totalInterestPayable) {
            this.totalInterestPayable = totalInterestPayable;
            return this;
        }

        public Builder riskCategory(String riskCategory) {
            this.riskCategory = riskCategory;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder timestamp(LocalDateTime timestamp) {
            if (timestamp != null) {
                this.timestamp = timestamp.atZone(ZoneId.of("UTC")).toInstant();
            }
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public LoanPricingEvent build() {
            return new LoanPricingEvent(
                requestId, customerId, productType, loanAmount,
                interestRate, emiAmount, loanTenureMonths,
                totalPayableAmount, totalInterestPayable,
                riskCategory, status, timestamp != null ? timestamp : Instant.now(), traceId
            );
        }
    }

    @Override
    public String toString() {
        return "LoanPricingEvent{" +
                "requestId=" + requestId +
                ", customerId='" + customerId + '\'' +
                ", productType='" + productType + '\'' +
                ", loanAmount=" + loanAmount +
                ", interestRate=" + interestRate +
                ", emiAmount=" + emiAmount +
                ", status='" + status + '\'' +
                ", timestamp=" + timestamp +
                ", traceId='" + traceId + '\'' +
                '}';
    }
}
