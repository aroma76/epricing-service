-- ═══════════════════════════════════════════════════════════════════════════
-- V8__reconcile_schema_and_range_indexes.sql
-- Fixes YugabyteDB HASH index degradation, outcome column overflow, and EXPIRED constraint.
-- Note: Pure DDL statements only (no PL/pgSQL dollar-quoted blocks) for ScriptUtils compatibility.
-- ═══════════════════════════════════════════════════════════════════════════

-- 1. Expand audit outcome to support states like 'SKIPPED_TERMINAL_STATE' (22 chars)
ALTER TABLE pricing_audit_logs ALTER COLUMN outcome TYPE VARCHAR(50);

-- 2. Drop HASH-partitioned created_at indexes and recreate with DESC range ordering
-- In YugabyteDB DocDB, secondary indexes without ASC/DESC default to HASH sharding,
-- which disables index scans for ORDER BY created_at DESC and BETWEEN range scans.
DROP INDEX IF EXISTS idx_created_at;
CREATE INDEX idx_created_at ON pricing_requests (created_at DESC);

DROP INDEX IF EXISTS idx_audit_created_at;
CREATE INDEX idx_audit_created_at ON pricing_audit_logs (created_at DESC);

-- Composite range index for fast customer fraud/audit trail lookups
-- Compatible with YugabyteDB (hash sharded on leading key, range sorted on created_at DESC), PostgreSQL, and H2
CREATE INDEX IF NOT EXISTS idx_audit_cust_created ON pricing_audit_logs (customer_id, created_at DESC);

-- 3. Ensure EXPIRED status is allowed by status check constraint
ALTER TABLE pricing_requests DROP CONSTRAINT IF EXISTS pricing_requests_status_check;
ALTER TABLE pricing_requests ADD CONSTRAINT pricing_requests_status_check 
    CHECK (status IN ('PENDING', 'CALCULATED', 'APPROVED', 'REJECTED', 'EXPIRED', 'ERROR'));
