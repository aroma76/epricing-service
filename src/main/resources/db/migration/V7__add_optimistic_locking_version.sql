-- ═══════════════════════════════════════════════════════════════════════════
-- V7__add_optimistic_locking_version.sql
-- ISSUE-07 FIX: Add optimistic locking version column to pricing_requests.
-- Hibernate @Version will include "WHERE version = ?" in all UPDATE statements,
-- preventing lost updates when concurrent approval and expiration activities
-- race to modify the same PricingRequest row within the same millisecond window.
-- ═══════════════════════════════════════════════════════════════════════════
ALTER TABLE pricing_requests ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
