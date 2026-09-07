-- ═══════════════════════════════════════════════════════════════════════════
-- V2__create_pricing_audit_logs_table.sql
-- Enterprise Schema Migration: Pricing Audit Logs Table (Compliance & Regulatory)
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS pricing_audit_logs (
    id                  BIGSERIAL PRIMARY KEY,
    pricing_request_id  BIGINT,
    customer_id         VARCHAR(50),
    action              VARCHAR(100) NOT NULL,
    description         TEXT,
    performed_by        VARCHAR(100),
    outcome             VARCHAR(20),
    metadata            TEXT,
    request_ip          VARCHAR(50),
    user_agent          VARCHAR(500),
    trace_id            VARCHAR(64),
    duration_ms         BIGINT,
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
