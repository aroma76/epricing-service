-- ═══════════════════════════════════════════════════════════════════════════
-- V6__create_grafana_reader_role.sql
-- Security Hardening: Least-privilege read-only role permissions for Grafana reporting
-- Note: The role 'grafana_reader' is provisioned idempotently by DatabaseMigrationInitializer.
--
-- ISSUE-11 FIX: Removed hardcoded 'DATABASE epricingdb' grant to support multi-environment
-- deployments (e.g. epricing_staging, epricing_prod_pci). CONNECT grant is dynamically
-- applied by DatabaseMigrationInitializer using the active catalog name. Pure DDL below
-- preserves compatibility with Spring ScriptUtils without PL/pgSQL dollar-quoted blocks.
-- ═══════════════════════════════════════════════════════════════════════════

GRANT USAGE ON SCHEMA public TO grafana_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO grafana_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO grafana_reader;
