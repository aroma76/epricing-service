-- ═══════════════════════════════════════════════════════════════════════════
-- V1__create_pricing_requests_table.sql
-- Enterprise Schema Migration: Pricing Requests Table
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS pricing_requests (
    id                      BIGSERIAL PRIMARY KEY,
    customer_id             VARCHAR(50)    NOT NULL,
    product_type            VARCHAR(50)    NOT NULL,
    loan_amount             DECIMAL(15, 2) NOT NULL,
    loan_tenure_months      INTEGER        NOT NULL,
    credit_score            INTEGER,
    annual_income           DECIMAL(15, 2),
    loan_purpose            VARCHAR(200),
    total_payable_amount    DECIMAL(15, 2),
    total_interest_payable  DECIMAL(15, 2),
    risk_category           VARCHAR(30),
    calculated_rate         DECIMAL(5, 2),
    emi_amount              DECIMAL(15, 2),
    status                  VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    processing_time_ms      BIGINT,
    request_ip              VARCHAR(50),
    trace_id                VARCHAR(64),
    created_at              TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP WITHOUT TIME ZONE
);
