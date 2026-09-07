-- ===========================================================================
-- V5__add_error_message_to_pricing_requests.sql
-- Adds error_message column so Grafana can show job failure reasons
-- without anyone needing to log into YugabyteDB directly.
-- ===========================================================================

ALTER TABLE pricing_requests
    ADD COLUMN IF NOT EXISTS error_message VARCHAR(2000);
