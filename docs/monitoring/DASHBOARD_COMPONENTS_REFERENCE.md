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
│ 1. System & DB Workload Summary │ 2. Throughput Volume    │ 3. Target Response Times   │
│ 4. HTTP 4XX Client Errors       │ 5. HTTP 5XX Server Errs │ 6. DB Pool & Long Queries  │
│ 7. Live DB Integration Table    │ 8. Error & Rejected Jobs Root Cause Drill-Down       │
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
  - 🟢 **Good:** `< 50%` — Healthy headroom for traffic spikes.
  - 🟡 **Warning:** `50% – 80%` — Elevated load; monitor thread concurrency.
  - 🔴 **Bad:** `>= 80%` — Severe CPU contention; leads to thread starvation and latency degradation.
- **Operator Action:** Check thread concurrency (`pricing_requests_active`). If load is legitimate, scale microservice replicas (Horizontal Pod Autoscaler).

---

### Panel: Memory Utilization
- **Component Type:** Stat Gauge (Colored threshold)
- **Query:** `clamp_min(sum(jvm_memory_used_bytes{application="epricing-service",area="heap"}) / clamp_min(sum(jvm_memory_max_bytes{application="epricing-service",area="heap"} > 0), 1) * 100, 0) or vector(0)`
- **Unit:** Percentage (`%`)
- **What it means:** Proportion of allocated JVM Heap currently holding live Java objects.
- **Thresholds:**
  - 🟢 **Good:** `< 60%` — Regular garbage collection cycles maintaining adequate free heap.
  - 🟡 **Warning:** `60% – 85%` — Elevated heap occupancy; monitor GC pause duration.
  - 🔴 **Bad:** `>= 85%` — High risk of `java.lang.OutOfMemoryError: Java heap space` and JVM crash.
- **Operator Action:** Capture an immediate heap dump: `jcmd <pid> GC.heap_dump /tmp/heap.hprof`. Check for leaking collections or oversized cached entities.

---

## 1.2 Throughput Volume & Target Response Times (Split Rows)

### Panels: GUI Requests / API Requests / Mobile & Other (Row `91`)
- **Component Type:** Full-Width Stat Row (`3 x w=8`)
- **Queries:**
  - GUI: `sum(increase(http_server_requests_seconds_count{application="epricing-service",method="GET"}[$__range])) or vector(0)`
  - API: `sum(increase(http_server_requests_seconds_count{application="epricing-service",method="POST",uri=~".*/pricing.*"}[$__range])) or vector(0)`
  - Mobile / Other: `sum(increase(http_server_requests_seconds_count{application="epricing-service"}[$__range])) or vector(0)`
- **Unit:** Request Count
- **What it means:** Segmentation of traffic by caller medium. `POST .*/pricing.*` represents core loan calculation and workflow operations; `GET` represents queries and health/UI views; Mobile / Other captures total incoming traffic across all endpoints.
- **Thresholds:** Always rendered green (`#9FC119`) for volume tracking; monitor alongside the error rows below.
- **Operator Action:** Inspect edge routing, ingress controllers, and SSL/TLS termination proxies if request volume drops unexpectedly to `0`.

---

### Panels: GUI (p95 ms) / API (p95 ms) / Mobile (p50 μs) (Row `94`)
- **Component Type:** Full-Width Stat Row (`3 x w=8`) with SLA thresholds
- **Queries:**
  - GUI (p95): `histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{application="epricing-service",method="GET"}[5m])) by (le)) * 1000`
  - API (p95): `histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{application="epricing-service",method="POST"}[5m])) by (le)) * 1000`
  - Mobile (p50): `histogram_quantile(0.50, sum(rate(http_server_requests_seconds_bucket{application="epricing-service",uri=~".*/actuator.*|.*/metrics-demo.*"}[5m])) by (le)) * 1000000`
- **Unit:** Milliseconds (`ms`) for API/GUI; Microseconds (`μs`) for actuator/demo probes.
- **What it means:** Latency percentile indicators across read, write, and internal probe endpoints.
- **Thresholds:**
  - **GUI (p95 ms) & API (p95 ms):** 🟢 Good `< 500 ms` | 🟡 Warning `500 ms – 2000 ms` | 🔴 Bad `>= 2000 ms` (RBI SLA breach)
  - **Mobile (p50 μs):** 🟢 Good `< 200,000 µs` (200 ms) | 🟡 Warning `200,000 – 500,000 µs` | 🔴 Bad `>= 500,000 µs`
- **Operator Action:**
  1. Check `DB Connection Acquire Time (ms)` panel in the Complete Observability dashboard.
  2. Check Hikari active/pending connections and YugabyteDB CPU.

---

## 1.3 HTTP 4XX Client Errors & HTTP 5XX Server Errors (Split Rows)

### Panels: GUI (4XX), API (4XX), Mobile (4XX) (Row `92`)
- **Component Type:** Full-Width Stat Row (`3 x w=8`)
- **Queries:**
  - `GUI (4XX)`: `sum(increase(http_server_requests_seconds_count{application="epricing-service",status=~"4..",method="GET"}[$__range])) or vector(0)`
  - `API (4XX)`: `sum(increase(http_server_requests_seconds_count{application="epricing-service",status=~"4..",method="POST"}[$__range])) or vector(0)`
  - `Mobile (4XX)`: `sum(increase(http_server_requests_seconds_count{application="epricing-service",status=~"4..",uri=~".*/actuator.*|.*/metrics-demo.*"}[$__range])) or vector(0)`
- **Unit:** Count
- **What it means:** Client-side validation rejections (HTTP 400 Bad Request, 422 Unprocessable Entity). In loan pricing, these indicate underwriting policy rejections (credit score < 650, FOIR > 50%, tenure out of bounds).
- **Thresholds:**
  - 🟢 **Good:** `0`
  - 🔴 **Alert Tile (`>= 1`):** Any 4XX turns the tile red so L1 operators inspect the *❌ Error & Rejected Jobs Table* to confirm whether it is a normal underwriting rejection or malformed client payload.
- **Operator Action:** Inspect the *❌ Error & Rejected Jobs Table* to verify if rejections are legitimate underwriting denials or invalid schema attributes.

---

### Panels: GUI (5XX), API (5XX), Mobile (5XX) (Row `95`)
- **Component Type:** Full-Width Stat Row (`3 x w=8`) (Strictly Red Alerting)
- **Queries:**
  - `GUI (5XX)`: `sum(increase(http_server_requests_seconds_count{application="epricing-service",status=~"5..",method="GET"}[$__range])) or vector(0)`
  - `API (5XX)`: `sum(increase(http_server_requests_seconds_count{application="epricing-service",status=~"5..",method="POST"}[$__range])) or vector(0)`
  - `Mobile (5XX)`: `sum(increase(http_server_requests_seconds_count{application="epricing-service",status=~"5..",uri=~".*/actuator.*|.*/metrics-demo.*"}[$__range])) or vector(0)`
- **Unit:** Count
- **What it means:** Internal server crashes, database connection failures, unhandled runtime exceptions, or Kafka broker timeout failures.
- **Thresholds:**
  - 🟢 **Good:** **Strictly `0`**.
  - 🔴 **Bad (`>= 1`):** Immediate investigation required; any `5XX` turns the tile red.
- **Operator Action:** Immediately open *Log Analysis Dashboard* and filter `level="ERROR"` to extract the Java exception stack trace, or inspect the *Error & Rejected Jobs Table*.

---

## 1.4 Database Health · Connection Pool · Blockings & Long Queries

### Panel: DB Blockings (Transaction)
- **Component Type:** Stat Counter
- **Query:** `hikaricp_connections_pending{application="epricing-service",pool="epricing-pool"}`
- **Unit:** Number of threads waiting
- **What it means:** The count of Java HTTP threads that requested a database connection from HikariCP but were forced to wait because pool connections are busy.
- **Thresholds:**
  - 🟢 **Good:** **Strictly `0`**.
  - 🔴 **Bad (`>= 1`):** Threads are queued waiting for DB connections; if sustained, HTTP requests will time out with 500 errors.
- **Operator Action:** Check if long-running queries are holding connections. Verify DB CPU/latency before adjusting `spring.datasource.hikari.maximum-pool-size`.

---

### Panel: DB Blockings (Reporting) & DB Blocker Count
- **Component Type:** Stat Counter
- **Query:** `hikaricp_connections_timeout_total{application="epricing-service",pool="epricing-pool"}`
- **Unit:** Cumulative Count
- **What it means:** Count of connection requests that exceeded the `connection-timeout` limit (30,000 ms) and threw `SQLTransientConnectionException`.
- **Thresholds:**
  - 🟢 **Good:** **Strictly `0`**.
  - 🔴 **Bad (`>= 1`):** Any connection acquisition timeout turns both tiles red and indicates severe pool exhaustion or DB lock contention.
- **Operator Action:** Verify YugabyteDB process health: `bin/ysqlsh -p 5433 -U yugabyte -c "SELECT * FROM pg_stat_activity WHERE state != 'idle';"`.

---

### Panel: DB Sleeping Sessions, DB Logins (Transaction), DB Logins (Reporting)
- **Component Type:** Stat Counter
- **Queries:**
  - `DB Sleeping Sessions` / `DB Logins (Reporting)`: `hikaricp_connections_idle{application="epricing-service",pool="epricing-pool"}`
  - `DB Logins (Transaction)`: `hikaricp_connections_active{application="epricing-service",pool="epricing-pool"} + hikaricp_connections_idle{application="epricing-service",pool="epricing-pool"}`
- **Unit:** Connection Count
- **What it means:** `Sleeping Sessions` / `DB Logins (Reporting)` track idle connections in `epricing-pool`. `DB Logins (Transaction)` tracks total open connections (`active + idle`) in `epricing-pool`.
- **Thresholds:**
  - **DB Logins (Transaction):** 🟢 Good `< 7` | 🟡 Warning `7 – 9` | 🔴 Bad `>= 10` (pool saturated at default max size)
  - **DB Sleeping Sessions & DB Logins (Reporting):** Green (`#9FC119`)
- **Operator Action:** Check network stability and connection leak warnings if total logins stay pinned at `>= 10`.

---

### Panel: Long Running Query (TXN / RPT)
- **Component Type:** Stat Counter
- **Queries:**
  - `Long Running Query (TXN)`: `increase(pricing_requests_total{application="epricing-service",type="failed"}[5m])`
  - `Long Running Query (RPT)`: `clamp_min(sum(increase(http_server_requests_seconds_bucket{application="epricing-service",le="+Inf"}[5m])) - sum(increase(http_server_requests_seconds_bucket{application="epricing-service",le="2.0"}[5m])), 0) or vector(0)`
- **Unit:** Count
- **What it means:** Pricing calculation failures (`TXN`) or HTTP requests exceeding the 2,000ms SLA threshold (`RPT`) over the last 5 minutes.
- **Thresholds:**
  - 🟢 **Good:** **Strictly `0`**.
  - 🔴 **Bad (`>= 1`):** Any calculation failure or `> 2.0s` transaction in the last 5m triggers red alerting.
- **Operator Action:** Verify range indexes exist on `created_at DESC` per migration `V8__reconcile_schema_and_range_indexes.sql`.

---

## 1.5 Live Database Integration & Error Drill-Down Tables

### Panel: Integration Jobs — All Pricing Requests (`id=51`)
- **Component Type:** Interactive Table (`cellHeight: "md"`, `h=14`, live SQL from YugabyteDB)
- **Query:**
  ```sql
  SELECT id AS "Job ID", customer_id AS "Customer", product_type AS "Product",
         status AS "Status", created_at AT TIME ZONE 'Asia/Kolkata' AS "Started At",
         processing_time_ms AS "Duration (ms)", calculated_rate AS "Rate %",
         emi_amount AS "EMI (INR)", <derived Error Summary>,
         COALESCE(error_message, '') AS "Full Error (DB)", trace_id AS "Trace ID"
  FROM pricing_requests ORDER BY created_at DESC LIMIT 500;
  ```
- **Operator Usage:** Real-time visibility into incoming loans without requiring SSH or database access. Clicking any `Trace ID` opens the trace in Grafana Tempo.
- **Status Badge Mapping:**
  - 🟢 `CALCULATED` / `APPROVED` -> `✅ COMPLETED`
  - 🟡 `PENDING` -> `⏳ RUNNING`
  - 🟠 `REJECTED` -> `⚠️ REJECTED`
  - 🔴 `ERROR` -> `❌ ERROR`

---

### Panel: ❌ Error & Rejected Jobs — Root Cause Drill-Down (`id=52`)
- **Component Type:** Interactive Table (`cellHeight: "md"`, `h=14`)
- **Query:**
  ```sql
  SELECT id AS "Job ID", customer_id AS "Customer", product_type AS "Product",
         status AS "Status", created_at AT TIME ZONE 'Asia/Kolkata' AS "Failed At",
         processing_time_ms AS "Duration", <categorized Root Cause>,
         COALESCE(error_message, 'No error message stored in DB') AS "Full Stack (DB)",
         trace_id AS "Trace ID"
  FROM pricing_requests
  WHERE status IN ('ERROR', 'REJECTED')
  ORDER BY created_at DESC LIMIT 200;
  ```
- **Operator Usage:** Direct answer for customer support: categorizes common business rejections and DB/timeout errors into human-readable `Root Cause` messages alongside the complete stored `Full Stack (DB)` error and clickable `Trace ID`.

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
| **Active In-Flight** | `pricing_requests_active` | Gauge (Count) | `< 20` | `20 – 49` | `>= 50` | Threads backing up; inspect downstream Temporal/Kafka bottlenecks. |
| **Heap Memory Used** | `jvm_memory_used_bytes / max * 100` | Percentage | `< 60%` | `60% – 85%` | `>= 85%` | Trigger GC profile and verify heap memory leaks. |
| **DB Pool Active** | `hikaricp_connections_active` | Gauge (0–10) | `< 7` | `7 – 9` | `>= 10` | DB connection pool near exhaustion; investigate slow queries. |

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
  - 🟢 **Good:** `< 50 ms` (financial math is in-memory and fast).
  - 🟡 **Warning:** `50 ms – 200 ms`.
  - 🔴 **Bad:** `>= 200 ms` — CPU throttling or thread scheduling delays.

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
| **epricing-service CPU %** | cAdvisor `container_cpu_usage_seconds_total` | `%` of 1 Core | `< 50%` | `50% – 80%` | `>= 80%` | Scale pod CPU limits or add container replicas. |
| **epricing-service Memory** | cAdvisor `container_memory_working_set_bytes` | Bytes (MB) | `< 500 MB` | `500 – 800 MB` | `>= 800 MB` | Nearing container limit; inspect JVM heap usage and container limits. |
| **YugabyteDB CPU %** | cAdvisor `container_cpu_usage_seconds_total` | `%` | `< 50%` | `50% – 80%` | `>= 80%` | Heavy query load. Check for missing indexes or table locks. |
| **YugabyteDB Memory** | cAdvisor `container_memory_working_set_bytes` | Bytes (MB/GB) | `< 500 MB` | `500 MB – 1 GB` | `>= 1 GB` | DocDB block cache saturation. Review memory limits in docker-compose. |
| **Node CPU Idle %** | Node Exporter `node_cpu_seconds_total{mode="idle"}` | `%` | `>= 40%` | `20% – 40%` | `< 20%` | Host machine/Cloud PC is running out of processing capacity. |
| **Node Memory Available** | Node Exporter `node_memory_MemAvailable_bytes` | Bytes (GB) | `>= 2.0 GB` | `1.0 – 2.0 GB` | `< 1.0 GB` | Host OS memory pressure; risk of host system thrashing. |

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
| **ERROR Log Count (last range)** | `sum(count_over_time({application="epricing-service", level="ERROR"}[$__range]))` | Count | **`0`** | N/A | `>= 1` | Unhandled exceptions occurring. Check live stream panel for stack trace. |
| **WARN Log Count (last range)** | `sum(count_over_time({application="epricing-service", level="WARN"}[$__range]))` | Count | **`0`** | `>= 1` | N/A | Frequent policy rejections, retries, or rate-lock signal warnings. |
| **INFO Log Count (last range)** | `sum(count_over_time({application="epricing-service", level="INFO"}[$__range]))` | Count | Proportional to requests | `0` (no logs) | Massive flood | If `0`, check Promtail/Loki ingestion; if flooding, verify log level config. |
| **DB Errors (5XX from Pricing)** | `sum(increase(pricing_errors_total{application="epricing-service",error_type="PRICING_CALCULATION_FAILED"}[$__range])) or vector(0)` | Count | **`0`** | N/A | `>= 1` | Database write/commit failures during loan persistence. |
| **Simulated Errors (Load Test)** | `pricing_demo_errors_total{application="epricing-service",error_type="SIMULATED_ERROR"}` | Counter | **`0`** | `>= 1` (expected during demo traffic) | N/A | Verifies alert routing and traffic generator error simulation. |
| **Slow Requests Demo Counter** | `pricing_demo_slow_requests_total{application="epricing-service"}` | Counter | **`0`** | `>= 1` (expected during demo traffic) | N/A | Verifies latency alerting on simulated slow requests. |

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
