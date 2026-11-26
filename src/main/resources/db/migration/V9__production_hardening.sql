-- ═══════════════════════════════════════════════════════════════════════════
-- V9__production_hardening.sql
-- Adds idempotency protection, persisted rate validity, workflow correlation,
-- and transactional outbox persistence for guaranteed Kafka event delivery.
-- Pure DDL only (no PL/pgSQL dollar-quoted blocks) for Spring ScriptUtils,
-- YugabyteDB YSQL, PostgreSQL, and H2 MODE=PostgreSQL compatibility.
-- ═══════════════════════════════════════════════════════════════════════════

-- 1. Add idempotency key, Temporal workflow correlation, and persisted quote expiry
ALTER TABLE pricing_requests ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);
ALTER TABLE pricing_requests ADD COLUMN IF NOT EXISTS workflow_id VARCHAR(128);
ALTER TABLE pricing_requests ADD COLUMN IF NOT EXISTS rate_valid_until TIMESTAMP WITH TIME ZONE;

-- Unique constraint prevents duplicate loan quotes across client/gateway/Temporal retries
-- Standard SQL UNIQUE index permits multiple NULLs while enforcing uniqueness on non-null keys
CREATE UNIQUE INDEX IF NOT EXISTS idx_pricing_idempotency_key ON pricing_requests (idempotency_key);
CREATE INDEX IF NOT EXISTS idx_pricing_workflow_id ON pricing_requests (workflow_id);

-- 2. Transactional Outbox table for atomic DB commit + reliable Kafka relay
CREATE TABLE IF NOT EXISTS outbox_events (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_type  VARCHAR(50)              NOT NULL,
    aggregate_id    BIGINT                   NOT NULL,
    event_type      VARCHAR(50)              NOT NULL,
    topic           VARCHAR(128)             NOT NULL,
    message_key     VARCHAR(128)             NOT NULL,
    payload         TEXT                     NOT NULL,
    trace_id        VARCHAR(64),
    status          VARCHAR(20)              NOT NULL DEFAULT 'PENDING',
    retry_count     INTEGER                  NOT NULL DEFAULT 0,
    max_retries     INTEGER                  NOT NULL DEFAULT 10,
    next_retry_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_by       VARCHAR(100),
    locked_until    TIMESTAMP WITH TIME ZONE,
    last_error      VARCHAR(2000),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at    TIMESTAMP WITH TIME ZONE,
    version         BIGINT                   NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_outbox_poll ON outbox_events (status, next_retry_at ASC);
CREATE INDEX IF NOT EXISTS idx_outbox_agg ON outbox_events (aggregate_type, aggregate_id);

ALTER TABLE outbox_events DROP CONSTRAINT IF EXISTS outbox_events_status_check;
ALTER TABLE outbox_events ADD CONSTRAINT outbox_events_status_check
    CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'DEAD_LETTER'));
