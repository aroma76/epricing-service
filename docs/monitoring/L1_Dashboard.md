# L1 Operations Dashboard

> **File:** `grafana/dashboards/epricing-l1-operations-dashboard.json`  
> **URL:** `http://localhost:3000` -> Dashboards -> ePricing L1 Operations Dashboard  
> **Datasources:** Prometheus (`prometheus-uid`) + YugabyteDB/PostgreSQL (`yugabytedb-uid`)  
> **Auto-refresh:** 10 seconds | **Default range:** Last 30 minutes | **Timezone:** `Asia/Kolkata`  
> **Detailed Component & Threshold Reference:** [`DASHBOARD_COMPONENTS_REFERENCE.md`](./DASHBOARD_COMPONENTS_REFERENCE.md#1-epricing-l1-operations-dashboard)  

## Purpose

Built for first-line support teams to monitor ePricing health without needing knowledge of
Prometheus queries, databases, or log files.

Styled after the CRMNXT internal L1 dashboard:
- Dark background
- Lime-green (`#9FC119`) = healthy
- Yellow = warning
- Red (`#E02F44`) = alert, needs action
- Full-width rows (`3` cards at `w=8` or `4` cards at `w=6` per row) separated by transparent spacers so Grafana keeps visual gaps between sections

## Dashboard Panels

### 1. System & Database Workload Summary (Row `90`)

| Panel | Source | Threshold |
|-------|--------|-----------|
| `Total Jobs (DB)` | YugabyteDB (`pricing_requests`) | Green (`#9FC119`) |
| `Enabled Jobs (DB)` | YugabyteDB (`CALCULATED`, `APPROVED`) | Green (`#9FC119`) |
| `CPU Utilization` | Prometheus (`process_cpu_usage`) | Yellow `>= 50%`, Red `>= 80%` |
| `Memory Utilization` | Prometheus (JVM heap `%`) | Yellow `>= 60%`, Red `>= 85%` |

### 2. Throughput Volume (Row `91`)

| Panel | Query Scope | Threshold |
|-------|-------------|-----------|
| `GUI Requests` | `GET` requests over `$__range` | Green (`#9FC119`) |
| `API Requests` | `POST` calls matching `.*/pricing.*` over `$__range` | Green (`#9FC119`) |
| `Mobile / Other` | Total HTTP requests over `$__range` | Green (`#9FC119`) |

### 3. Target Response Times — Latency SLAs (Row `94`)

| Panel | Query Scope | Threshold |
|-------|-------------|-----------|
| `GUI (p95 ms)` | `GET` `5m` p95 latency | Yellow `>= 500 ms`, Red `>= 2000 ms` |
| `API (p95 ms)` | `POST` `5m` p95 latency (RBI SLA `< 2000 ms`) | Yellow `>= 500 ms`, Red `>= 2000 ms` |
| `Mobile (p50 μs)` | `/actuator` & `/metrics-demo` `5m` p50 latency | Yellow `>= 200,000 µs`, Red `>= 500,000 µs` |

### 4. HTTP 4XX Client Errors (Row `92`) & HTTP 5XX Server Errors (Row `95`)

| Row | Panels | Query Scope | Threshold |
|-----|--------|-------------|-----------|
| `HTTP 4XX Client Errors` | `GUI (4XX)`, `API (4XX)`, `Mobile (4XX)` | `GET`, `POST`, and `/actuator\|/metrics-demo` `4xx` counts | Red `>= 1` |
| `HTTP 5XX Server Errors` | `GUI (5XX)`, `API (5XX)`, `Mobile (5XX)` | `GET`, `POST`, and `/actuator\|/metrics-demo` `5xx` counts | Red `>= 1` |

### 5. Database Health · Connection Pool · Blockings & Long Running Queries (Row `93`)

| Panel | Metric | Threshold |
|-------|--------|-----------|
| `DB Blockings (Transaction)` | `hikaricp_connections_pending` | Red `>= 1` |
| `DB Blockings (Reporting)` | `hikaricp_connections_timeout_total` | Red `>= 1` |
| `DB Blocker Count` | `hikaricp_connections_timeout_total` | Red `>= 1` |
| `DB Sleeping Sessions` | `hikaricp_connections_idle` | Green (`#9FC119`) |
| `DB Logins (Transaction)` | `hikaricp_connections_active + idle` | Yellow `>= 7`, Red `>= 10` |
| `DB Logins (Reporting)` | `hikaricp_connections_idle` | Green (`#9FC119`) |
| `Long Running Query (TXN)` | `increase(pricing_requests_total{type="failed"}[5m])` | Red `>= 1` |
| `Long Running Query (RPT)` | HTTP requests `> 2.0s` over `5m` | Red `>= 1` |

## Integration Jobs & Error Drill-Down Tables (Rows `50` & `53`)

Core panels fulfilling: *"details for all running, complete or error jobs... no need to login into DB to check error"*.

- **Datasource:** YugabyteDB (`yugabytedb-uid`) queried via Grafana's built-in PostgreSQL plugin.
- **Row `50` (`Integration Jobs — All Pricing Requests`, `LIMIT 500`):** Columns `Job ID`, `Customer`, `Product`, `Status`, `Started At`, `Duration (ms)`, `Rate %`, `EMI (INR)`, `Error Summary`, `Full Error (DB)`, `Trace ID`.
- **Row `53` (`❌ Error & Rejected Jobs — Root Cause Drill-Down`, `LIMIT 200`):** Filters `status IN ('ERROR', 'REJECTED')` with columns `Job ID`, `Customer`, `Product`, `Status`, `Failed At`, `Duration`, `Root Cause`, `Full Stack (DB)`, `Trace ID`.

Status badges:
- `CALCULATED` / `APPROVED` -> `✅ COMPLETED` (green)
- `PENDING` -> `⏳ RUNNING` (yellow)
- `REJECTED` -> `⚠️ REJECTED` (orange)
- `ERROR` -> `❌ ERROR` (red) — `Error Summary` / `Root Cause` & full DB message visible in-table; clicking `Trace ID` opens the trace in Grafana Tempo.

## How Error Messages Get into the DB

1. `PricingService.calculatePricing(...)` intentionally runs without an outer `@Transactional` wrapper so atomic DB writes stay confined to short-lived transactions in `PricingAuditService`.
2. When a technical failure occurs, the catch block calls `auditService.recordTechnicalFailure(requestDto, failureDetail, requestIp, traceId)`.
3. `PricingAuditService.recordTechnicalFailure(...)` saves a `PricingRequest` row with `status = ERROR` and `error_message` (truncated safely to 2000 chars) alongside an audit entry in `pricing_audit_logs` within its own `@Transactional` commit.
4. Grafana queries `pricing_requests` directly and surfaces the `ERROR` / `REJECTED` row and trace link without requiring DB shell access.

## Grafana Across Support Tiers

| Level | Team           | Dashboard                        | Purpose |
|-------|----------------|----------------------------------|---------|
| L1    | Support agents | L1 Operations Dashboard          | Monitor health, drill into failed jobs, escalate on red tiles |
| L2    | Senior support | Log Analysis Dashboard           | Investigate Loki logs by `customerId` / `traceId` |
| L3    | Developers     | Complete Observability Dashboard | JVM, latency percentiles, product mix, DB pool deep dive |
| L4    | Architects     | Container & Node Utilization     | Container CPU/memory, network I/O, host capacity planning |
