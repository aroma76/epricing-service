# Enterprise ePricing — Master Dashboard Component & Operational Threshold Reference Guide

This reference guide provides an exhaustive, production-grade technical and operational manual for all **4 Grafana dashboards** in the Bank ePricing microservice ecosystem.

Designed for **L1 Operations Support**, **Site Reliability Engineers (SREs)**, and **DevOps Engineers**, this document details the architectural meaning, exact metric queries, measurement units, healthy vs. critical thresholds, and immediate incident remediation workflows for every panel.

---

## Quick Navigation

1. [Dashboard 1: ePricing L1 Operations Dashboard](#1-epricing-l1-operations-dashboard)
2. [Dashboard 2: ePricing Complete Observability Dashboard](#2-epricing-complete-observability-dashboard)
3. [Dashboard 3: ePricing Container & Node Utilization](#3-epricing-container--node-utilization)
4. [Dashboard 4: ePricing Log Analysis Dashboard](#4-epricing-log-analysis-dashboard)
5. [Operational Escalation Matrix](#5-operational-escalation-matrix)

---

# 1. ePricing L1 Operations Dashboard

- **File:** `grafana/dashboards/epricing-l1-operations-dashboard.json`
- **Datasources:** Prometheus (`http://prometheus:9090`) & YugabyteDB (`yugabytedb:5433/epricingdb`)
- **Refresh Rate:** 10 seconds | **Default Range:** Last 30 minutes
- **Target Audience:** L1 Support Specialists, Operations Command Center, Loan Underwriting Support

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ 1. System & DB Workload Summary │ 2. Throughput & Latency SLAs │ 3. 4XX vs 5XX Errors  │
│ 4. DB Pool Health & Blockings   │ 5. Live DB Integration Table │ 6. Error Drill-Down   │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 1.1 System & Database Workload Summary

### Panel: Total Jobs (DB)
- **Component Type:** Stat Gauge
- **Query / Source:** `SELECT count(*) FROM pricing_requests` (YugabyteDB SQL)
- **Unit:** Count (Integer)
- **What it means:** The cumulative total count of loan pricing requests created and stored in the primary database.
- **Thresholds:**
  - 🟢 **Good:** `> 0` and monotonically growing during business hours.
  - 🟡 **Warning:** Stagnant (flatline) during peak banking hours (09:00 - 18:00 IST), indicating an upstream ingress or gateway outage.
  - 🔴 **Bad:** `0` on a running production environment or query returning SQL connection error.
- **Operator Action:** If value fails to load or drops to zero, verify YugabyteDB container status (`docker compose ps yugabytedb`) and JDBC connection pool status.

---

### Panel: Enabled Jobs (DB)
- **Component Type:** Stat Gauge
- **Query / Source:** `SELECT count(*) FROM pricing_requests WHERE status IN ('PENDING', 'CALCULATED', 'APPROVED')`
- **Unit:** Count (Integer)
- **What it means:** Active, non-terminated loan requests in the workflow pipeline that are either awaiting customer rate-lock decision or successfully approved.
- **Thresholds:**
  - 🟢 **Good:** Dynamic count tracking active pipeline volume.
  - 🟡 **Warning:** Sudden drop (> 50% drop within 1 hour) without corresponding customer activity.
  - 🔴 **Bad:** `0` during active operational periods.
- **Operator Action:** Check if the Temporal workflow worker is stalling or failing to record quotes into the database.

---

### Panel: CPU Utilization
- **Component Type:** Stat Gauge (Colored threshold)
- **Query:** `process_cpu_usage{application="epricing-service"} * 100`
- **Unit:** Percentage (`%`)
- **What it means:** Percentage of dedicated CPU capacity consumed by the Spring Boot JVM process.
- **Thresholds:**
  - 🟢 **Good:** `< 60%` — Healthy headroom for traffic spikes.
  - 🟡 **Warning:** `60% – 80%` — Sustained load; thread pool saturation possible under sudden bursts.
  - 🔴 **Bad:** `> 80%` — Severe CPU contention; leads to thread starvation and latency degradation.
- **Operator Action:** Check thread concurrency (`pricing_requests_active`). If load is legitimate, scale microservice replicas (Horizontal Pod Autoscaler).

---

### Panel: Memory Utilization
- **Component Type:** Stat Gauge (Colored threshold)
- **Query:** `(sum(jvm_memory_used_bytes{application="epricing-service",area="heap"}) / sum(jvm_memory_max_bytes{application="epricing-service",area="heap"})) * 100`
- **Unit:** Percentage (`%`)
- **What it means:** Proportion of allocated JVM Heap currently holding live Java objects.
- **Thresholds:**
  - 🟢 **Good:** `< 70%` — Regular garbage collection cycles maintaining adequate free heap.
  - 🟡 **Warning:** `70% – 85%` — Frequent GC pause cycles; monitor GC pause duration.
  - 🔴 **Bad:** `> 85%` — High risk of `java.lang.OutOfMemoryError: Java heap space` and JVM crash.
- **Operator Action:** Capture an immediate heap dump: `jcmd <pid> GC.heap_dump /tmp/heap.hprof`. Check for leaking collections or oversized cached entities.

---

## 1.2 Throughput Volume & Target Response Times

### Panels: GUI Requests / API Requests / Mobile & Other
- **Component Type:** Stat Gauges
- **Queries:**
  - GUI: `sum(increase(http_server_requests_seconds_count{application="epricing-service",method="GET"}[$__range]))`
  - API: `sum(increase(http_server_requests_seconds_count{application="epricing-service",method="POST"}[$__range]))`
  - Mobile/Internal: `sum(increase(http_server_requests_seconds_count{application="epricing-service",uri=~".*/actuator.*|.*/metrics-demo.*"}[$__range]))`
- **Unit:** Request Count
- **What it means:** Segmentation of traffic by caller medium. POST requests represent core loan calculation and workflow operations; GET requests represent queries and dashboard visual views.
- **Thresholds:**
  - 🟢 **Good:** Proportional distribution consistent with daily traffic curves.
  - 🟡 **Warning:** Zero POST requests while GET requests continue, indicating core calculation API path is failing at the API gateway.
  - 🔴 **Bad:** Sudden drop to 0 across all channels simultaneously.
- **Operator Action:** Inspect edge routing, ingress controllers, and SSL/TLS termination proxies.

---

### Panels: GUI (p95 ms) / API (p95 ms) / Mobile (p50 μs)
- **Component Type:** Stat Gauges with SLA thresholds
- **Queries:**
  - GUI (p95): `histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{application="epricing-service",method="GET"}[5m])) by (le)) * 1000`
  - API (p95): `histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{application="epricing-service",method="POST"}[5m])) by (le)) * 1000`
  - Mobile (p50): `histogram_quantile(0.50, sum(rate(http_server_requests_seconds_bucket{application="epricing-service",uri=~".*/actuator.*|.*/metrics-demo.*"}[5m])) by (le)) * 1000000`
- **Unit:** Milliseconds (`ms`) for API/GUI; Microseconds (`μs`) for internal probes.
- **What it means:** The 95th percentile response time. 95% of customer transactions complete faster than this number.
- **Thresholds:**
  - 🟢 **Good:** `< 200 ms` for API; `< 100 ms` for GUI; `< 5000 μs` (5 ms) for internal probes.
  - 🟡 **Warning:** `200 ms – 500 ms` (API) — Degraded service level; loan calculation taking longer than normal.
  - 🔴 **Bad:** `> 500 ms` (API) — Direct SLA violation in retail digital banking.
- **Operator Action:**
  1. Check `DB Connection Acquire Time (ms)` panel. If DB acquire > 50ms, pool is congested.
  2. Check Hikari active connections and YugabyteDB CPU.

---

## 1.3 HTTP 4XX Client Errors & HTTP 5XX Server Errors

### Panels: GUI (4XX), API (4XX), Mobile (4XX)
- **Component Type:** Stat Counters
- **Query:** `sum(increase(http_server_requests_seconds_count{application="epricing-service",status=~"4..",method="POST"}[$__range])) or vector(0)`
- **Unit:** Count
- **What it means:** Client-side validation rejections (HTTP 400 Bad Request, 422 Unprocessable Entity). In loan pricing, these indicate underwriting policy rejections (credit score < 650, FOIR > 50%, tenure out of bounds).
- **Thresholds:**
  - 🟢 **Good:** `1 – 50` per hour (indicates normal rejected customer loan applications).
  - 🟡 **Warning:** `> 20%` of total request volume — Possible malformed payload rollout from mobile/web frontend team.
  - 🔴 **Bad:** Sudden spike to `> 80%` of all traffic — Client frontend integration bug or automated bot attack.
- **Operator Action:** Inspect the *❌ Error & Rejected Jobs Table* to verify if rejections are legitimate underwriting denials or invalid schema attributes.

---

### Panels: GUI (5XX), API (5XX), Mobile (5XX)
- **Component Type:** Stat Counters (Strictly Red Alerting)
- **Query:** `sum(increase(http_server_requests_seconds_count{application="epricing-service",status=~"5..",method="POST"}[$__range])) or vector(0)`
- **Unit:** Count
- **What it means:** Internal server crashes, database connection failures, unhandled runtime exceptions, or Kafka broker timeout failures.
- **Thresholds:**
  - 🟢 **Good:** **Strictly `0`**.
  - 🟡 **Warning:** `1 – 3` (Transient failure, e.g. temporary DB connection retry).
  - 🔴 **Bad:** `> 5` — Immediate severity-1 incident. Application is failing to serve loan quotes.
- **Operator Action:** Immediately open *Log Analysis Dashboard* and filter `level="ERROR"` to extract the Java exception stack trace.

---

## 1.4 Database Health · Connection Pool · Blockings & Long Queries

### Panel: DB Blockings (Transaction)
- **Component Type:** Stat Counter
- **Query:** `hikaricp_connections_pending{application="epricing-service",pool="epricing-pool"}`
- **Unit:** Number of threads waiting
- **What it means:** The count of Java HTTP threads that requested a database connection from HikariCP but were forced to sleep because all 10 connections are busy.
- **Thresholds:**
  - 🟢 **Good:** **Strictly `0`**.
  - 🟡 **Warning:** `1 – 3` threads pending — Pool approaching exhaustion under load burst.
  - 🔴 **Bad:** `> 5` threads pending — Impending thread starvation; HTTP requests will soon time out with 500 errors.
- **Operator Action:** Check if long-running queries are holding connections. Increase `spring.datasource.hikari.maximum-pool-size` if DB CPU is low.

---

### Panel: DB Blockings (Reporting) & DB Blocker Count
- **Component Type:** Stat Counter
- **Query:** `hikaricp_connections_timeout_total{application="epricing-service",pool="epricing-pool"}`
- **Unit:** Cumulative Count
- **What it means:** Count of connection requests that exceeded the `connection-timeout` limit (30,000 ms) and threw `SQLTransientConnectionException`.
- **Thresholds:**
  - 🟢 **Good:** **Strictly `0`**.
  - 🟡 **Warning:** `1` (Isolated connection acquisition failure).
  - 🔴 **Bad:** `> 2` — Severe connection pool exhaustion or database freeze.
- **Operator Action:** Verify YugabyteDB process health: `bin/ysqlsh -p 5433 -U yugabyte -c "SELECT * FROM pg_stat_activity WHERE state != 'idle';"`.

---

### Panel: DB Sleeping Sessions & DB Logins
- **Component Type:** Stat Counter
- **Queries:**
  - Sleeping (Idle): `hikaricp_connections_idle{application="epricing-service",pool="epricing-pool"}`
  - Logins (Total): `hikaricp_connections_active{...} + hikaricp_connections_idle{...}`
- **Unit:** Connection Count
- **What it means:** `Sleeping Sessions` are idle connections in the pool waiting for queries. `Logins` is total open TCP sockets to YugabyteDB (default pool size: 10).
- **Thresholds:**
  - 🟢 **Good:** Idle between `2` and `10`; Total Logins stable at `10`.
  - 🟡 **Warning:** Idle drops to `0` continuously for > 2 minutes.
  - 🔴 **Bad:** Total Logins drops below `5` while application is running (indicates DB dropping TCP sockets).
- **Operator Action:** Check network stability between application pods and YugabyteDB.

---

### Panel: Long Running Query (TXN / RPT)
- **Component Type:** Stat Counter
- **Query:** `increase(pricing_requests_total{application="epricing-service",type="failed"}[5m])` and requests exceeding `le="2.0"` seconds.
- **Unit:** Count
- **What it means:** Transactions or queries taking more than 2,000ms to complete.
- **Thresholds:**
  - 🟢 **Good:** **Strictly `0`**.
  - 🟡 **Warning:** `1 – 2` during database backup or vacuum operations.
  - 🔴 **Bad:** `> 5` — Table scan or unindexed query bottlenecking database tablets.
- **Operator Action:** Verify range indexes exist on `created_at DESC` per migration `V8__reconcile_schema_and_range_indexes.sql`.

---

## 1.5 Live Database Integration & Error Drill-Down Tables

### Panel: Integration Jobs — All Pricing Requests
- **Component Type:** Interactive Table (Live SQL from YugabyteDB)
- **Query:**
  ```sql
  SELECT id AS "Job ID", customer_id AS "Customer", product_type AS "Product",
         loan_amount AS "Loan Amount", interest_rate AS "Rate %", emi_amount AS "EMI",
         status AS "Status", created_at AS "Submitted At"
  FROM pricing_requests ORDER BY created_at DESC LIMIT 50;
  ```
- **Operator Usage:** Real-time visibility into incoming loans without requiring SSH or database access.
- **Status Color Code:**
  - 🟢 `CALCULATED` / `APPROVED`: Offer created or accepted.
  - 🟡 `PENDING`: Awaiting calculation.
  - ⚪ `EXPIRED`: 7-day rate-lock expired without customer response.
  - 🔴 `REJECTED` / `ERROR`: Application rejected or calculation aborted.

---

### Panel: ❌ Error & Rejected Jobs — Root Cause Drill-Down
- **Component Type:** Interactive Table
- **Query:**
  ```sql
  SELECT p.id AS "Job ID", p.customer_id AS "Customer", p.product_type AS "Product",
         p.status AS "Status", a.outcome AS "Rejection Reason", a.details AS "Audit Detail",
         p.created_at AS "Failed At"
  FROM pricing_requests p
  LEFT JOIN pricing_audit_logs a ON p.id = a.pricing_request_id
  WHERE p.status IN ('REJECTED', 'ERROR')
  ORDER BY p.created_at DESC LIMIT 25;
  ```
- **Operator Usage:** Direct answer for customer support: when a branch officer asks *"Why was loan ID 1042 rejected?"*, this table displays the exact policy rule failure (`PRICING_INSUFFICIENT_CREDIT_SCORE: Score 580 < 650 threshold`).

---

# 2. ePricing Complete Observability Dashboard

- **File:** `grafana/dashboards/epricing-complete-dashboard.json`
- **Datasource:** Prometheus
- **Target Audience:** SREs, System Architects, Performance Engineers
- **Purpose:** Full-spectrum telemetry covering the **Google SRE 4 Golden Signals** (Latency, Traffic, Errors, Saturation).

---

## 2.1 Service Health Overview

| Panel Name | Query | Unit | 🟢 Good | 🟡 Warning | 🔴 Bad | Operator Action Plan |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Service Status** | `up{job="epricing-service"}` | Binary (0 or 1) | `1` | N/A | `0` | Service down or Actuator unreachable. Inspect pod logs & Kubernetes events. |
| **Total Requests** | `pricing_requests_total{type="all"}` | Counter | Steadily increasing | Flatline during peak hours | Decreasing / reset unexpectedly (indicates container restart) | Check pod restart count (`kubectl get pods`). |
| **Successful Pricings** | `pricing_requests_total{type="success"}` | Counter | `> 90%` of all requests | `80% – 90%` | `< 80%` | Inspect rejection causes in audit log table. |
| **Failed Pricings** | `pricing_requests_total{type="failed"}` | Counter | `0` | `1 – 5` | `> 5` | Check database and rule engine exception logs. |
| **Active In-Flight** | `pricing_requests_active` | Gauge (Count) | `0 – 5` | `6 – 20` | `> 20` | Threads backing up; inspect downstream Temporal/Kafka bottlenecks. |
| **Heap Memory Used** | `jvm_memory_used_bytes / max * 100` | Percentage | `< 70%` | `70% – 85%` | `> 85%` | Trigger GC profile and verify heap memory leaks. |
| **DB Pool Active** | `hikaricp_connections_active` | Gauge (0–10) | `0 – 4` | `5 – 8` | `> 8` | DB connection pool near exhaustion; investigate slow queries. |

---

## 2.2 Latency SLAs & Time Percentiles

### Panel: Response Time Percentiles (p50 / p95 / p99)
- **Component Type:** Multi-Line Time Series
- **Queries:**
  - `p50`: `histogram_quantile(0.50, sum(rate(http_server_requests_seconds_bucket[5m])) by (le)) * 1000`
  - `p95`: `histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le)) * 1000`
  - `p99`: `histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket[5m])) by (le)) * 1000`
- **Unit:** Milliseconds (`ms`)
- **What it means:** The latency distribution curve across all HTTP requests.
- **Thresholds:**
  - 🟢 **Good:** `p50 < 30ms`, `p95 < 200ms`, `p99 < 500ms`.
  - 🟡 **Warning:** `p95 > 250ms` or `p99 > 800ms` — Significant tail latency; 1 out of 100 customers experiences lag.
  - 🔴 **Bad:** `p95 > 500ms` or `p99 > 2000ms` — Critical SLA degradation.
- **Operator Action:** Correlate with JVM GC pause time graph. If GC pause coincides with p99 spikes, tune G1GC flags (`-XX:MaxGCPauseMillis=100`).

---

## 2.3 Pricing Business Metrics

### Panel: Requests by Product Type
- **Component Type:** Horizontal Bar Gauge
- **Query:** `sum by (product) (pricing_product_requests_total{application="epricing-service"})`
- **Unit:** Request Count
- **What it means:** Business demand distribution across:
  - `HOME_LOAN`
  - `PERSONAL_LOAN`
  - `AUTO_LOAN`
  - `BUSINESS_LOAN`
- **Interpretation:** In banking operations, sudden complete absence of one product type indicates a misconfigured frontend route or underwriting configuration outage for that product.

---

### Panel: Pricing Calculation Latency SLA
- **Component Type:** Multi-Stat SLA Gauge
- **Query:** `histogram_quantile(0.95, sum(rate(pricing_calculation_duration_seconds_bucket[5m])) by (le)) * 1000`
- **Unit:** Milliseconds (`ms`)
- **What it means:** Measures the isolated execution time of the financial math algorithm (reducing balance interest, EMI calculation, FOIR underwriting rules) excluding network transmission time.
- **Thresholds:**
  - 🟢 **Good:** `< 15 ms` (financial math is in-memory and extremely fast).
  - 🟡 **Warning:** `15 ms – 50 ms`.
  - 🔴 **Bad:** `> 50 ms` — CPU throttling or thread scheduling delays.

---

## 2.4 Database Health & JVM Runtime Metrics

### Panel: DB Connection Acquire Time (ms)
- **Component Type:** Time Series
- **Query:** `rate(hikaricp_connections_acquire_seconds_sum[1m]) / rate(hikaricp_connections_acquire_seconds_count[1m]) * 1000`
- **Unit:** Milliseconds (`ms`)
- **What it means:** Average time a thread spends waiting to checkout a physical connection from HikariCP.
- **Thresholds:**
  - 🟢 **Good:** `< 5 ms` — Connections readily available.
  - 🟡 **Warning:** `5 ms – 50 ms` — Contention developing.
  - 🔴 **Bad:** `> 50 ms` — Immediate thread blocking risk.

---

### Panel: JVM Memory — Heap Used vs Max
- **Component Type:** Time Series Area Chart
- **Query:** `jvm_memory_used_bytes{area="heap"}` plotted alongside `jvm_memory_max_bytes{area="heap"}`
- **Pattern Interpretation:**
  - 🟢 **Healthy (Sawtooth Pattern):** Memory steadily rises as requests are handled, then drops sharply after Young Generation GC runs.
  - 🔴 **Unhealthy (Staircase Pattern):** Memory rises after GC cycles and the baseline floor continuously creeps higher toward `max`, indicating a classic Java memory leak.

---

# 3. ePricing Container & Node Utilization

- **File:** `grafana/dashboards/container-nodes-pods-utilization.json`
- **Datasource:** Prometheus (scraped from cAdvisor & Node Exporter)
- **Target Audience:** Infrastructure Engineers, Cloud Platform Ops, SREs
- **Purpose:** Hardware and virtualization telemetry across host CPU, memory, container boundaries, and network interfaces.

---

## 3.1 Container Resource Summary

| Panel | Metric Source | Unit | 🟢 Good | 🟡 Warning | 🔴 Bad | Operator Action Plan |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **epricing-service CPU %** | cAdvisor `container_cpu_usage_seconds_total` | `%` of 1 Core | `< 70%` | `70% – 90%` | `> 90%` | Scale pod CPU limits or add container replicas. |
| **epricing-service Memory** | cAdvisor `container_memory_working_set_bytes` | Bytes (MB/GB) | `< 700 MB` (Limit: 1 GB) | `700 – 900 MB` | `> 900 MB` | Nearing container limit; Linux kernel will issue `OOMKill (Exit 137)`. Increase container memory limit. |
| **YugabyteDB CPU %** | cAdvisor `container_cpu_usage_seconds_total` | `%` | `< 60%` | `60% – 85%` | `> 85%` | Heavy query load. Check for missing indexes or table locks. |
| **YugabyteDB Memory** | cAdvisor `container_memory_working_set_bytes` | Bytes (GB) | `< 2.5 GB` (Limit: 3 GB) | `2.5 – 2.8 GB` | `> 2.8 GB` | DocDB block cache saturation. Tune memory limits in docker-compose. |
| **Node CPU Idle %** | Node Exporter `node_cpu_seconds_total{mode="idle"}` | `%` | `> 40%` | `15% – 40%` | `< 15%` | Host machine/Cloud PC is running out of processing capacity. |
| **Node Memory Available** | Node Exporter `node_memory_MemAvailable_bytes` | Bytes (GB) | `> 2.0 GB` | `1.0 – 2.0 GB` | `< 1.0 GB` | Host OS memory thrashing; risk of host system freeze. |

---

## 3.2 Network I/O & CPU Breakdown

### Panels: Network Receive / Transmit (bytes/s)
- **Component Type:** Time Series
- **Query:** `sum by (interface) (rate(container_network_receive_bytes_total{interface!~"lo|br-.*"}[1m]))`
- **Unit:** Bytes per second (`B/s`, `KB/s`, `MB/s`)
- **What it means:** Network throughput across physical/virtual network cards (excluding loopback `lo` and Docker bridge `br-*` interfaces).
- **Thresholds:**
  - 🟢 **Good:** Healthy flow proportional to request volume (`50 KB/s – 5 MB/s`).
  - 🟡 **Warning:** Sustained throughput `> 80 MB/s` on a 1 Gbps link.
  - 🔴 **Bad:** Sudden drop to `0 B/s` while application is under load (network interface failure or DNS failure).

---

### Panel: Node CPU by Mode
- **Component Type:** Multi-Line Time Series
- **Query:** `avg(rate(node_cpu_seconds_total[1m])) by (mode) * 100`
- **Modes Monitored:**
  - `user`: Normal application execution.
  - `system`: OS kernel system calls (network stack, memory paging).
  - `iowait`: CPU sitting idle waiting for disk I/O.
- **Thresholds:**
  - 🟢 **Good:** `iowait < 5%`, `system < 20%`.
  - 🔴 **Bad:** `iowait > 20%` — Severe disk bottleneck (slow NVMe/SSD or storage queue depth exhausted).

---

# 4. ePricing Log Analysis Dashboard

- **File:** `grafana/dashboards/log-analysis-dashboard.json`
- **Datasource:** Grafana Loki (`http://loki:3100`) via Promtail log shipping
- **Target Audience:** Application Developers, L2 Support, Bug Investigation
- **Purpose:** Centralized, structured JSON log stream aggregation and error frequency correlation.

---

## 4.1 Log Volume Overview

| Panel | Query | Unit | 🟢 Good | 🟡 Warning | 🔴 Bad | Operator Action Plan |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **ERROR Log Count** | `sum(count_over_time({application="epricing-service", level="ERROR"}[$__range]))` | Count | **`0`** | `1 – 5` | `> 5` | Unhandled exceptions occurring. Check live stream panel for stack trace. |
| **WARN Log Count** | `sum(count_over_time({application="epricing-service", level="WARN"}[$__range]))` | Count | Low & stable | Spike `> 50` | `> 200` | Frequent policy rejections, retries, or rate-lock signal warnings. |
| **INFO Log Count** | `sum(count_over_time({application="epricing-service", level="INFO"}[$__range]))` | Count | Proportional to requests | 0 (no logs) | Massive flood (`> 10,000/min`) | If 0, Promtail or Loki is down; if flooding, log level set to debug in production. |
| **DB Errors (5XX)** | `pricing_errors_total{error_type="PRICING_CALCULATION_FAILED"}` | Counter | **`0`** | `1` | `> 2` | Database write/commit failures during loan persistence. |
| **Simulated Errors** | `pricing_demo_errors_total{error_type="SIMULATED_ERROR"}` | Counter | Increments every ~150s | Flat | N/A | Verifies alert routing and traffic generator liveness. |

---

## 4.2 Error Analysis & Trends

### Panel: All Error Types (Cumulative)
- **Component Type:** Bar Gauge
- **Query:** `sum by (error_type) (pricing_errors_total{application="epricing-service"})`
- **Error Categories Cataloged:**
  - `PRICING_INSUFFICIENT_CREDIT_SCORE`: Customer score < 650 policy minimum.
  - `PRICING_FOIR_EXCEEDED`: Debt-to-income ratio > 50% threshold.
  - `PRICING_CALCULATION_FAILED`: Database or math calculation crash.
  - `PRICING_UNSUPPORTED_PRODUCT`: Invalid loan product code received.
- **Interpretation:** Distinguishes customer business disqualifications from system code defects.

---

## 4.3 Live Streaming Logs

### Panel: ERROR Logs — Live Stream
- **Component Type:** Live Logs Console
- **Query:** `{application="epricing-service", level="ERROR"}`
- **What it displays:** Structured JSON logs containing:
  - `@timestamp`: ISO-8601 millisecond timestamp.
  - `logger_name`: Class originating the exception.
  - `message`: Human-readable error message.
  - `customerId`: Masked customer ID (`CUST****-001`) for banking compliance.
  - `stack_trace`: Complete Java exception stack trace.
- **Operator Usage:** Click on any log line to copy the stack trace and attach directly to Jira/incident tickets.

---

### Panel: Pricing Calculation Logs
- **Component Type:** Live Logs Console
- **Query:** `{application="epricing-service"} |~ "pricing|PRICING|ePricing"`
- **What it displays:** Granular step-by-step trace of active calculations:
  - Input parameters: Principal, tenure, credit score, annual income.
  - Computed base rate, risk premium adjustments, large loan discounts.
  - Output EMI and assigned risk tier (`EXCELLENT`, `GOOD`, `FAIR`).

---

# 5. Operational Escalation Matrix

Use this matrix to determine incident severity when dashboard panels breach thresholds:

| Trigger Condition | Severity | Initial Response Time | Escalation Path |
| :--- | :--- | :--- | :--- |
| **`Service Status = 0`** or **`API (5XX) > 5`** | **P1 — Critical Outage** | `< 5 minutes` | Notify SRE Lead, On-Call Engineer, and Database Admin. |
| **`DB Blockings (Pending) > 5`** or **`DB Connection Timeout > 0`** | **P1 — Database Deadlock** | `< 5 minutes` | Notify Database Admin to terminate blocking backend PID. |
| **`API Response Time (p95) > 500ms`** | **P2 — Performance Degradation** | `< 15 minutes` | Notify Core ePricing Development Team and SRE. |
| **`Memory Utilization > 85%`** | **P2 — Impending OOM** | `< 15 minutes` | Scale container memory / trigger heap dump for dev team. |
| **`API 4XX Errors > 30% of traffic`** | **P3 — Customer Impact** | `< 30 minutes` | Notify Digital Channels / Frontend Portal Integration Team. |
| **`Simulated Errors flatlined`** | **P4 — Monitoring Drill Failure** | `< 2 hours` | Inspect `traffic-generator` container and script. |
