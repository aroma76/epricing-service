-- ═══════════════════════════════════════════════════════════════════════════
-- V3__create_indexes.sql
-- Performance Indexes for High-Concurrency Lookups & Range Scans
-- ═══════════════════════════════════════════════════════════════════════════

-- pricing_requests indexes
CREATE INDEX IF NOT EXISTS idx_customer_id   ON pricing_requests(customer_id);
CREATE INDEX IF NOT EXISTS idx_product_type  ON pricing_requests(product_type);
CREATE INDEX IF NOT EXISTS idx_status        ON pricing_requests(status);
CREATE INDEX IF NOT EXISTS idx_created_at    ON pricing_requests(created_at);
CREATE INDEX IF NOT EXISTS idx_trace_id      ON pricing_requests(trace_id);

-- pricing_audit_logs indexes
CREATE INDEX IF NOT EXISTS idx_audit_pricing_request_id ON pricing_audit_logs(pricing_request_id);
CREATE INDEX IF NOT EXISTS idx_audit_customer_id        ON pricing_audit_logs(customer_id);
CREATE INDEX IF NOT EXISTS idx_audit_action             ON pricing_audit_logs(action);
CREATE INDEX IF NOT EXISTS idx_audit_created_at         ON pricing_audit_logs(created_at);
CREATE INDEX IF NOT EXISTS idx_audit_trace_id           ON pricing_audit_logs(trace_id);
