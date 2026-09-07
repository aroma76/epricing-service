# L1 Operations Dashboard

> File: grafana/dashboards/epricing-l1-operations-dashboard.json
> URL: http://localhost:3000 -> Bank ePricing Monitoring -> ePricing L1 Operations Dashboard
> Datasources: Prometheus (metrics) + YugabyteDB/PostgreSQL (job records)
> Auto-refresh: 10 seconds | Default range: Last 30 minutes

## Purpose

Built for first-line support teams to monitor ePricing health without needing knowledge of
Prometheus queries, databases, or log files.

Styled after the CRMNXT internal L1 dashboard:
- Dark background
- Lime-green (#9FC119) = healthy
- Red (#E02F44) = alert, needs action
- Simple large numbers - no graphs, no noise

## Dashboard Panels

### Error Rate

| Panel | Threshold |
|-------|-----------|
| 4XX Errors 24h | Orange >= 1 |
| 4XX Errors 1h  | Orange >= 1 |
| 4XX Rate/min   | Orange > 5/min |
| 5XX Errors 24h | RED >= 1 (any 5XX = critical) |
| 5XX Errors 1h  | RED >= 1 |
| 5XX Rate/min   | Red > 0.1/min |

### Response Time

| Panel | Threshold |
|-------|-----------|
| p50 Response Time | Yellow >200ms, Red >500ms |
| p95 Response Time | Yellow >500ms, Red >2000ms |
| p99 Response Time | Yellow >1000ms, Red >3000ms |

### Database Connection Pool

| Panel | Threshold |
|-------|-----------|
| DB Pool Used % | Yellow >60%, Red >80% |
| DB Pool Idle % | Red <20% |
| Total DB Sessions | Red >10 |
| Active DB Sessions | Red >9 |
| DB Blockings (Pending) | Red >= 1 |
| DB Blocker Count (Timeouts) | Red >= 1 |

### Request Counts

| Panel | Threshold |
|-------|-----------|
| Total Requests 24h | Green always |
| Successful Pricings 24h | Green always |
| Active In-Flight | Yellow >20, Red >50 |
| Long Running Calc >1s | Red >= 1 |
| Long Running Request >2s | Red >= 1 |

## Integration Jobs Table

The core panel. Fulfils the requirement:
"details for all running, complete or error jobs... no need to login into DB to check error"

Datasource: YugabyteDB queried via Grafana's built-in PostgreSQL plugin.

Columns: Job ID, Customer, Product, Status, Started At, Duration, Error Reason, Trace ID

Status colours:
- CALCULATED -> COMPLETED (green)
- PENDING    -> RUNNING   (yellow)
- REJECTED   -> REJECTED  (orange)
- ERROR      -> ERROR     (red) -- Error Reason column shows exact failure message

## How Error Messages Get into the DB

1. A pricing request fails with a technical error
2. PricingService catch block calls auditService.recordTechnicalFailure()
3. recordTechnicalFailure() runs in its own @Transactional(REQUIRES_NEW) transaction
4. It saves a PricingRequest row: status=ERROR, error_message=exception.getMessage()
5. This transaction commits independently (even though the main request rolled back)
6. Grafana table shows the ERROR row with the exact error message visible

No DB login needed. Support team sees everything in the dashboard.

## Grafana Across Support Tiers

| Level | Team           | Dashboard                       | Purpose |
|-------|----------------|---------------------------------|---------|
| L1    | Support agents | L1 Operations Dashboard         | Monitor health, escalate on red tiles |
| L2    | Senior support | Log Analysis Dashboard          | Investigate logs by customerId / traceId |
| L3    | Developers     | Complete Observability Dashboard | JVM, GC, p99 latency, DB pool deep dive |
| L4    | Architects     | Container Node Dashboard        | Capacity planning, 30-day resource trends |
