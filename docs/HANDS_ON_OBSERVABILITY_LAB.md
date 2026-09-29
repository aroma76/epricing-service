# 🔬 ePricing Service — Hands-on Observability Lab & Incident Simulation Playbook

> **Target Audience:** Engineers, SREs, and trainees looking for a 100% practical, step-by-step guide to running, testing, troubleshooting, and diagnosing incidents on the ePricing microservice stack.

---

## Table of Contents

1. [Lab Prerequisites & Port Matrix](#1-lab-prerequisites--port-matrix)
2. [Step 1: Starting the Entire Stack Locally](#2-step-1-starting-the-entire-stack-locally)
3. [Step 2: API Verification & Playground](#3-step-2-api-verification--playground)
4. [Step 3: Simulating Incidents (Chaos & Anomaly Drills)](#4-step-3-simulating-incidents-chaos--anomaly-drills)
5. [Step 4: The Golden Triangle Incident Triage Drill](#5-step-4-the-golden-triangle-incident-triage-drill)
6. [Step 5: PromQL & LogQL Cheat Sheet](#6-step-5-promql--logql-cheat-sheet)
7. [Step 6: Common Local Troubleshooting Gotchas](#7-step-6-common-local-troubleshooting-gotchas)

---

## 1. Lab Prerequisites & Port Matrix

Before starting, ensure you have:
- **Docker & Docker Compose** installed and running (recommended: allocate at least 6–8 GB RAM in Docker Desktop).
- **Java 21 (LTS)** and **Maven 3.9+** (if compiling or running the app outside Docker).
- **curl** or **Postman**.

### Complete Service Port Matrix

| Service | Port | Description | URL / Verification |
|---|---|---|---|
| **ePricing Business API** | `8080` | Core REST APIs | `http://localhost:8080/api/v1/pricing` |
| **ePricing Management/Actuator** | `8081` | Actuator & Prometheus scrape endpoint | `http://localhost:8081/actuator/prometheus` |
| **Grafana UI** | `3000` | Dashboards, Log Explorer & Trace Explorer | `http://localhost:3000` (User: `admin` / `admin`) |
| **Prometheus UI** | `9090` | Metrics DB & PromQL query engine | `http://localhost:9090` |
| **Alertmanager UI** | `9093` | Alert routing and active alert state | `http://localhost:9093` |
| **Loki** | `3100` | Log database engine | `http://localhost:3100/ready` |
| **Grafana Tempo (OTLP HTTP)** | `4318` | Distributed tracing collector endpoint | `http://localhost:4318` |
| **YugabyteDB (YSQL)** | `5433` | Distributed PostgreSQL database | `localhost:5433` (db: `epricingdb`) |
| **YugabyteDB Web UI** | `15433` | Cluster management & node health | `http://localhost:15433` |
| **Webhook Logger** | `5001` | Local mock alert receiver | `http://localhost:5001` |
| **Node Exporter** | `9100` | Host OS & CPU/Memory hardware metrics | `http://localhost:9100/metrics` |
| **cAdvisor** | `8082` | Container resource utilization metrics | `http://localhost:8082` |

---

## 2. Step 1: Starting the Entire Stack Locally

### Option A: Complete Docker Compose Run (Recommended)

1. Set the mandatory fail-fast credentials (via `.env` or terminal):
   ```bash
   export DB_USERNAME=yugabyte
   export DB_PASSWORD=yugabyte
   export CORS_ALLOWED_ORIGINS="http://localhost:3000,http://localhost:8080"
   ```
   *(On Windows PowerShell:)*
   ```powershell
   $env:DB_USERNAME="yugabyte"
   $env:DB_PASSWORD="yugabyte"
   $env:CORS_ALLOWED_ORIGINS="http://localhost:3000,http://localhost:8080"
   ```

2. Start all containers in detached mode:
   ```bash
   docker-compose up -d
   ```

3. Watch container health:
   ```bash
   docker-compose ps
   ```
   > **Note:** YugabyteDB takes ~45–60 seconds to initialize distributed consensus before `epricing-service` finishes database migrations (V1 through V5).

---

### Option B: Running the Java Application Locally (IDE / CLI) with Docker Infra

If you want to edit code and debug in your IDE while running supporting infra in Docker:

1. Start only the databases and observability containers:
   ```bash
   docker-compose up -d yugabytedb prometheus grafana loki promtail tempo otel-collector alertmanager
   ```

2. Run the Spring Boot application locally:
   ```powershell
   $env:DB_HOST="localhost"
   $env:DB_PORT="5433"
   $env:DB_NAME="epricingdb"
   $env:DB_USERNAME="yugabyte"
   $env:DB_PASSWORD="yugabyte"
   $env:CORS_ALLOWED_ORIGINS="http://localhost:3000,http://localhost:8080"
   mvn spring-boot:run
   ```

3. Verify the app is up:
   ```bash
   curl http://localhost:8080/api/v1/health
   curl http://localhost:8081/actuator/health
   ```

---

## 3. Step 2: API Verification & Playground

Run these `curl` commands to test the banking logic and generate initial metrics and logs:

### 1. Calculate Pricing for a Home Loan (Eligible - Prime Customer)
```bash
curl -X POST http://localhost:8080/api/v1/pricing \
  -H "Content-Type: application/json" \
  -H "X-Customer-ID: CUST001234" \
  -d '{
    "customerId": "CUST001234",
    "productType": "HOME_LOAN",
    "loanAmount": 5000000,
    "tenureMonths": 240,
    "creditScore": 810,
    "annualIncome": 1800000
  }'
```
**Expected Response (`201 Created`):**
- `calculatedRate`: 8.00% (Base rate 8.5% + Home Loan multiplier discount)
- `emiAmount`: ~₹41,822.00
- `riskCategory`: `LOW_RISK`
- `status`: `APPROVED`
- Response Header contains: `X-Request-ID: REQ-...`

---

### 2. Calculate Pricing for a Personal Loan (Higher Risk)
```bash
curl -X POST http://localhost:8080/api/v1/pricing \
  -H "Content-Type: application/json" \
  -H "X-Customer-ID: CUST009876" \
  -d '{
    "customerId": "CUST009876",
    "productType": "PERSONAL_LOAN",
    "loanAmount": 500000,
    "tenureMonths": 36,
    "creditScore": 680,
    "annualIncome": 600000
  }'
```
**Expected Response (`201 Created`):**
- `calculatedRate`: 13.50% (Reflects unsecured personal loan risk)
- `riskCategory`: `MEDIUM_RISK`

---

### 3. Test Regulatory Rejection (CIBIL Score Below Minimum < 650)
```bash
curl -X POST http://localhost:8080/api/v1/pricing \
  -H "Content-Type: application/json" \
  -H "X-Customer-ID: CUST004321" \
  -d '{
    "customerId": "CUST004321",
    "productType": "AUTO_LOAN",
    "loanAmount": 800000,
    "tenureMonths": 60,
    "creditScore": 580,
    "annualIncome": 500000
  }'
```
**Expected Response (`422 Unprocessable Entity`):**
- Returns error details explaining credit score is below bank policy minimum (650).
- Check `pricing_requests_total{type="rejected"}` in Prometheus.

---

### 4. Query Customer Pricing History
```bash
curl http://localhost:8080/api/v1/pricing?customerId=CUST001234
```

---

## 4. Step 3: Simulating Incidents (Chaos & Anomaly Drills)

The service includes a built-in demo controller (`@Profile("!prod")`) mapped to `/api/v1/metrics-demo` specifically to train engineers on detecting anomalies.

### Drill 1: Simulate Latency Spike (p95 / Slow Response)
Inject an intentional 800ms–1500ms delay:
```bash
curl http://localhost:8080/api/v1/metrics-demo/simulate-slow
```
**What happens under the hood:**
- A warning log is created with a new `traceId`.
- An OpenTelemetry span event `"Starting intentional delay for demo"` is logged.
- The `pricing.demo.slow_requests` counter is bumped.

**How to verify:**
1. Open Grafana: `http://localhost:3000`
2. Open the **"ePricing Complete Dashboard"** (L3).
3. Observe the **p95 Latency** graph jump.

---

### Drill 2: Simulate Service Error Burst
Inject a controlled error:
```bash
curl http://localhost:8080/api/v1/metrics-demo/simulate-error
```
**What happens under the hood:**
- SLF4J JSON outputs a structured `ERROR` level log with event `SIMULATED_ERROR`.
- The OpenTelemetry span status is set to `StatusCode.ERROR`.
- Demo metric `pricing_demo_errors_total{error_type="SIMULATED_ERROR"}` increments.

---

### Drill 3: Generate Traffic Burst
Simulate 50–100 rapid requests across loan products:
```bash
curl http://localhost:8080/api/v1/metrics-demo/generate-load
```
**How to verify:**
- Go to Prometheus: `http://localhost:9090`
- Query: `rate(pricing_demo_load_test_total[1m])`
- Switch to the "Graph" tab to see the live rate spike.

---

### Drill 4: Inspect Live Trace Context
```bash
curl http://localhost:8080/api/v1/metrics-demo/trace-demo
```
Returns:
```json
{
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId": "00f067aa0ba902b7",
  "isSampled": true,
  "message": "Check Grafana Tempo for this trace: 4bf92f3577b34da6a3ce929d0e0e4736"
}
```
Copy the returned `traceId` for the triage drill below!

---

## 5. Step 4: The Golden Triangle Incident Triage Drill

This is the exact workflow SREs and Senior Engineers use during an on-call incident.

```
┌─────────────────┐       ┌─────────────────┐       ┌─────────────────┐
│   1. METRICS    │ ───►  │     2. LOGS     │ ───►  │    3. TRACES    │
│ (Prometheus UI) │       │   (Loki via     │       │   (Tempo via    │
│  Spot the spike │       │ Grafana Explore)│       │ Grafana Explore)│
│                 │       │ Extract traceId │       │ Find bottleneck │
└─────────────────┘       └─────────────────┘       └─────────────────┘
```

### Scenario: A Customer Reports a 500 Internal Server Error or High Latency

#### Step 1: Detect with Metrics
1. Open Grafana (`http://localhost:3000`) $\rightarrow$ Dashboards $\rightarrow$ **ePricing L1 Operations Dashboard**.
2. Notice the Error Rate card turned **RED** (> 5%) or the p95 latency spiked over 1000ms.
3. Note the exact timestamp of the spike.

#### Step 2: Correlate with Logs in Loki
1. In Grafana, click the **Explore** compass icon in the left navigation.
2. Select **Loki** from the datasource dropdown at the top.
3. Run this LogQL query:
   ```logql
   {application="epricing-service"} | json | level="ERROR"
   ```
4. Expand the newest log record. Notice the structured fields:
   ```json
   {
     "timestamp": "2026-09-17T10:30:15.123+05:30",
     "level": "ERROR",
     "logger": "com.bank.epricing.controller.MetricsDemoController",
     "message": "Simulated error event",
     "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
     "spanId": "00f067aa0ba902b7",
     "customerId": "CUST****1234"
   }
   ```
5. **Copy the `traceId` value.**

#### Step 3: Deep Dive with Distributed Tracing in Tempo
1. In the same Grafana window (or split screen), change the datasource dropdown to **Tempo**.
2. Select the **TraceQL** / **Search** tab $\rightarrow$ choose **Trace ID**.
3. Paste your `traceId` and press **Query**.
4. **Inspect the 3-Tier Waterfall:**
   - **Tier 1 (Controller):** `POST /api/v1/pricing` (Total Duration: 120ms)
   - **Tier 2 (Service):** Child span `calculatePricing` (112ms)
     - Child span `validateEligibility` (1ms)
     - Child span `computeInterestRate` (2ms)
     - Child span `calculateEmi` (1ms)
   - **Tier 3 (Repository):** Child span `persistPricingRequest` (105ms)
5. **Conclusion:** You immediately identify that the latency was in the database write layer (`persistPricingRequest`), not the mathematical calculation!

---

## 6. Step 5: PromQL & LogQL Cheat Sheet

Keep these project-specific queries handy:

### Essential PromQL Queries (Prometheus / Grafana)

| Purpose | PromQL Query |
|---|---|
| **Live Request Rate (RPS)** | `rate(http_server_requests_seconds_count{job="epricing-service"}[1m])` |
| **P95 Latency (Seconds)** | `histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{job="epricing-service"}[5m])) by (le))` |
| **Error Rate Percentage** | `(sum(rate(http_server_requests_seconds_count{job="epricing-service",status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count{job="epricing-service"}[5m]))) * 100` |
| **Active In-Flight Requests** | `pricing_requests_active{application="epricing-service"}` |
| **Calculations by Product Type** | `sum by (product_type) (rate(pricing_requests_total[5m]))` |
| **JVM Heap Memory Usage %** | `(jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}) * 100` |
| **Hikari Connection Pool Active** | `hikaricp_connections_active{pool="epricing-pool"}` |
| **Hikari Connection Pending (Starvation)** | `hikaricp_connections_pending{pool="epricing-pool"}` |

### Essential LogQL Queries (Loki / Grafana Explore)

| Purpose | LogQL Query |
|---|---|
| **All Service Logs** | `{application="epricing-service"}` |
| **Filter by Level (ERROR only)** | `{application="epricing-service"} \| json \| level="ERROR"` |
| **Filter by Customer ID (Masked)** | `{application="epricing-service"} \| json \| customerId="CUST****1234"` |
| **Filter by Slow Executions (> 500ms)** | `{application="epricing-service"} \| json \| durationMs > 500` |
| **Audit Operations** | `{application="epricing-service"} \| json \| event_type=~"AUDIT_.*"` |
| **Search by Trace ID** | `{application="epricing-service"} \| json \| traceId="YOUR_TRACE_ID"` |

---

## 7. Step 6: Common Local Troubleshooting Gotchas

### 1. Application Refuses to Start (`DB_USERNAME` / `DB_PASSWORD` error)
- **Cause:** Compliance hardening prevents using hardcoded fallback credentials.
- **Fix:** Ensure `$env:DB_USERNAME="yugabyte"` and `$env:DB_PASSWORD="yugabyte"` are set in your terminal before running `mvn spring-boot:run` or check the `environment:` section in `docker-compose.yml`.

### 2. YugabyteDB Connection Refused during initial startup
- **Cause:** YugabyteDB runs Raft consensus across distributed tablets, taking 30–60 seconds on initial creation.
- **Fix:** Check `docker logs -f yugabytedb`. Wait until you see `PostgreSQL server is ready to accept connections`.

### 3. OpenTelemetry Collector Connection Errors (`localhost:4318 connection refused`)
- **Cause:** If running `epricing-service` on your host machine while OTel Collector runs inside Docker, the collector must have port `4318` published to `localhost` in `docker-compose.yml`:
  ```yaml
  ports:
    - "4318:4318"  # OTLP HTTP
    - "4317:4317"  # OTLP gRPC
  ```

### 4. Promtail Not Ingesting Logs
- **Cause:** Path mismatch. Promtail tails `/app/logs/*.log`.
- **Fix:** Ensure the shared Docker volume `app-logs` is mounted to both `epricing-service` (`/app/logs`) and `promtail` (`/app/logs:ro`).

### 5. Docker Desktop Running Slow / Out of Memory
- **Fix:** If your PC has 8 GB RAM, temporarily disable non-essential monitoring containers:
  ```bash
  docker-compose stop cadvisor node-exporter alertmanager webhook-logger
  ```
  The core stack (`epricing-service`, `yugabytedb`, `prometheus`, `grafana`, `tempo`, `loki`) will run smoothly with ~3.5 GB of RAM.

---
*(End of Lab Playbook)*
