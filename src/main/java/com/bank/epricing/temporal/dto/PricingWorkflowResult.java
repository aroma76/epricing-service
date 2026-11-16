package com.bank.epricing.temporal.dto;

import com.bank.epricing.dto.PricingResponseDto;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

/**
 * Result object representing the state and outcome of a durable Temporal pricing workflow.
 * Adheres to Temporal determinism: timestamp tracking is driven by activities or workflow-deterministic context.
 */
public class PricingWorkflowResult implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonProperty("workflow_id")
    private String workflowId;

    @JsonProperty("customer_id")
    private String customerId;

    @JsonProperty("product_type")
    private String productType;

    @JsonProperty("status")
    private String status; // INITIALIZING, PROCESSING, QUOTED, APPROVED, REJECTED, EXPIRED

    @JsonProperty("rejection_reason")
    private String rejectionReason;

    @JsonProperty("quote")
    private PricingResponseDto quote;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;

    public PricingWorkflowResult() {}

    public PricingWorkflowResult(String workflowId, String customerId, String productType,
                                 String status, PricingResponseDto quote) {
        this.workflowId = workflowId;
        this.customerId = customerId;
        this.productType = productType;
        this.status = status;
        this.quote = quote;
        if (quote != null && quote.getCalculatedAt() != null) {
            this.createdAt = quote.getCalculatedAt().toString();
        } else {
            this.createdAt = null;
        }
        this.updatedAt = this.createdAt;
    }

    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public String getProductType() { return productType; }
    public void setProductType(String productType) { this.productType = productType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public PricingResponseDto getQuote() { return quote; }
    public void setQuote(PricingResponseDto quote) {
        this.quote = quote;
        if (quote != null && quote.getCalculatedAt() != null) {
            if (this.createdAt == null) {
                this.createdAt = quote.getCalculatedAt().toString();
            }
            this.updatedAt = quote.getCalculatedAt().toString();
        }
    }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
