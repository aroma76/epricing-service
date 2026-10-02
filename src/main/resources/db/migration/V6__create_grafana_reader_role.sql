-- ═══════════════════════════════════════════════════════════════════════════
-- V6__create_grafana_reader_role.sql
-- Security Hardening: Least-privilege read-only role for Grafana reporting
-- ═══════════════════════════════════════════════════════════════════════════

GRANT USAGE ON SCHEMA public TO grafana_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO grafana_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO grafana_reader;

