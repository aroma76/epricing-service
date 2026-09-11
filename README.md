# Bank — ePricing Monitoring System

A beginner-friendly, production-style Spring Boot application demonstrating real-world banking ePricing functionality and an end-to-end observability stack using **Prometheus**, **Grafana**, and **OpenTelemetry**.

---

## Table of Contents
1. [Overview & What Changed](#overview--what-changed)
2. [Why It Changed](#why-it-changed)
3. [Banking ePricing Application Domain](#banking-epricing-application-domain)
4. [Observability Stack Architecture](#observability-stack-architecture)
5. [How Prometheus, Grafana, and OpenTelemetry Work Together](#how-prometheus-grafana-and-opentelemetry-work-together)
6. [Project Structure](#project-structure)
7. [API Endpoints](#api-endpoints)
8. [Quickstart Guide](#quickstart-guide)

---

## Overview & What Changed

This project represents a simplified retail banking **ePricing (Electronic Pricing) Engine**. In commercial and retail banking, ePricing applications calculate custom interest rates, EMIs, and total repayable amounts based on loan product type, credit score, loan amount, and tenure.

### Summary of Improvements:

1. **Enhanced Banking Business Logic & Data Model**:
   - Expanded `PricingRequest` entity and database schema to store complete pricing inputs (`annualIncome`) and calculation results (`calculatedRate`, `emiAmount`, `totalPayableAmount`, `totalInterestPayable`, `riskCategory`, `status`, `processingTimeMs`, `traceId`).
   - Enabled retrieving pricing by ID (`GET /api/v1/pricing/{id}`), querying pricing history by customer ID (`GET /api/v1/pricing?customerId=...`), and fetching recent pricing records when no customer ID is specified.

2. **3-Tier OpenTelemetry Tracing**:
   - Auto-instrumented **Controller** layer via Spring WebMVC.
   - Explicit child spans for **Service** (`calculatePricing`, `validateEligibility`, `computeInterestRate`) and **Repository** (`PricingRepository.save`, `PricingRepository.findByCustomerId`, `PricingRepository.findById`) layers.
   - Formatted traces so Grafana Tempo displays a clear, 3-tier waterfall.

3. **Production-Style Metrics & Structured Logging**:
   - Micrometer custom metrics for total requests, successful calculations, failed calculations, active in-flight requests, average calculation duration, and loan amount distributions.
   - SLF4J JSON logging with MDC (Mapped Diagnostic Context) propagating `traceId`, `spanId`, `requestId`, and `customerId` on every log line.
   - Specific structured logs for:
     - Request received (`PRICING_STARTED`)
     - Calculation completed (`PRICING_COMPLETED`)
     - Validation failures (`VALIDATION_FAILURE`)
     - Exceptions (`PRICING_ERROR`)
     - Database operations & errors (`DB_OPERATION`, `DATABASE_ERROR`)

4. **Automated Unit & Controller Testing**:
   - Comprehensive JUnit 5 test suite (`PricingCalculatorTest`, `PricingServiceTest`, `PricingControllerTest`) covering mathematical logic, service orchestration, Bean Validation, and HTTP edge cases (400 validation failure, 404 not found, 422 insufficient credit score) — 17 tests total, all passing.

5. **Enterprise Hardening & Resilience**:
   - Dynamic application versioning via `BuildProperties` (auto-read from Maven `build-info.properties`).
   - Profile-isolated demonstration endpoints (`MetricsDemoController` gated with `@Profile("!prod")` to prevent metric pollution in production). Demo metrics isolated in `pricing.demo.*` namespace to prevent alert pollution.
   - Sequential, fail-fast distributed schema migration runner (`DatabaseMigrationInitializer`) tracking scripts in `schema_history`.
   - Externalized CORS allowed origin patterns via `CORS_ALLOWED_ORIGINS` environment variable. **Defaults to deny-all** if not set.

6. **Banking Compliance Hardening**:
   - **Actuator lockdown**: Restricted to `health`, `info`, `prometheus`, `metrics` only. Sensitive endpoints (`env`, `configprops`, `threaddump`) removed.
   - **DB credentials fail-fast**: No default fallback — application refuses to start if `DB_USERNAME`/`DB_PASSWORD` are not set in the environment.
   - **PII masked in all logs**: Customer IDs masked (`CUST****1234`), exact loan amounts replaced with range labels (`1L-10L`). No customer financial data stored in Loki.
   - **Synchronous audit trail**: `recordSuccess()` and `recordRejection()` run synchronously — regulatory audit records cannot be silently lost in a JVM crash.
   - **Proxy-aware IP capture**: `PricingController` reads `X-Forwarded-For` header to capture the real client IP, not the load balancer IP.
   - **FOIR check enforced**: `annualIncome` is now `@NotNull` — income eligibility check cannot be silently bypassed.
   - **Rate validity configurable**: `rateValidUntil` now driven by `epricing.rate-valid-days` config (default: 7 days).
   - **Enum cleanup**: Removed unused `PENDING` and `APPROVED` statuses that implied an unbuilt approval workflow.
   > See [`docs/compliance/COMPLIANCE_FIXES.md`](docs/compliance/COMPLIANCE_FIXES.md) for a full learning guide on every issue and fix.

7. **Container → Nodes → Pods Utilization Monitoring**:
   - Added **cAdvisor** (`cadvisor:8080`) to capture container-level CPU %, memory working set, disk I/O, and throttling metrics.
   - Added **Node Exporter** (`node-exporter:9100`) to expose host / node hardware utilization (Node CPU idle %, memory available %, filesystem size).
   - Created dedicated **Grafana Dashboard** (`container-nodes-pods-utilization.json`) visualizing Node, Pod, and Container utilization.


---

## Why It Changed

- **Beginner-Friendly Clarity**: Kept the architecture clean and simple (single Spring Boot app, H2 database) without introducing unnecessary enterprise complexity like Kafka, Redis, or Kubernetes.
- **Realistic Banking Context**: Transformed generic pricing placeholders into a realistic retail banking loan ePricing system (Home Loan, Auto Loan, Personal Loan, Business Loan, Education Loan).
- **Production Observability Practices**: Solved the "blind system" problem by correlating Metrics, Logs, and Traces via `traceId` so an intern or engineer can diagnose issues instantly in Grafana.

---

## Banking ePricing Application Domain

An **ePricing Engine** is a core component in digital banking onboarding and loan processing.

### Key Banking Concepts:
- **Base Rate**: Benchmark rate (e.g., 8.50% p.a.) defined by bank treasury / RBI regulations.
- **Risk Multiplier**: Product-specific risk weights (e.g., Home Loans carry lower risk at 1.0x because they are secured by property, whereas Personal Loans carry higher risk at 1.5x).
- **Credit Score Adjustment**: Customer CIBIL/Experian score rewards low-risk borrowers (e.g., score >= 800 gets a -1.00% rate discount).
- **Equated Monthly Installment (EMI)**: Calculated using standard reducing-balance compound interest formula:
  $$\text{EMI} = P \times r \times \frac{(1+r)^n}{(1+r)^n - 1}$$
- **FOIR (Fixed Obligation to Income Ratio)**: Regulatory safety threshold ensuring total monthly loan repayments do not exceed 50% of the customer's monthly income.

---

## Observability Stack Architecture

```
                               ┌─────────────────────────────────────────┐
                               │             Grafana                     │
                               │  (Dashboards, Logs, Traces Visualization)│
                               └────▲──────────────▲──────────────▲──────┘
                                    │              │              │
                       PromQL Query │   Loki Query │  OTLP Trace  │
                                    │              │              │
                    ┌───────────────┴──┐   ┌───────┴──────┐   ┌───┴──────────┐
                    │   Prometheus     │   │    Loki      │   │ OTel / Tempo │
                    │(Metrics Database)│   │(Log Storage) │   │   (Traces)   │
                    └───────▲──────────┘   └──────▲───────┘   └──────▲───────┘
                            │ Scrape /actuator    │ Promtail         │ OTLP HTTP
                            │ /prometheus         │ log tailing      │ :4318
                    ┌───────┴─────────────────────┴──────────────────┴──────┐
                    │               ePricing Spring Boot Service            │
                    │   [Controller]  --->  [Service]  --->  [Repository]   │
                    └───────────────────────────────────────────────────────┘
```

---

## How Prometheus, Grafana, and OpenTelemetry Work Together

1. **OpenTelemetry (Tracing & Telemetry Pipeline)**:
   - Captures distributed trace contexts (`traceId`, `spanId`) across Controller, Service, and Repository layers.
   - Exports trace spans to Grafana Tempo or OpenTelemetry Collector via OTLP (HTTP port 4318).
   - Injects the active `traceId` into SLF4J MDC so log messages automatically include the trace context.

2. **Prometheus (Metrics Collection & Storage)**:
   - Operates on a **PULL** model: periodically scrapes metric counters, timers, and gauges from Spring Boot's `/actuator/prometheus` endpoint (port 8081).
   - Stores time-series data for CPU utilization, JVM heap memory, HTTP request counts, latency percentiles (p95, p99), and business metrics (pricing requests, success/failure counts, average calculation duration).

3. **Grafana (Unified Single-Pane-of-Glass Dashboard)**:
   - Connects Prometheus, Loki, and Tempo as unified datasources.
   - Displays real-time charts for application health, business throughput, latency, and error rates.
   - Allows seamlessly clicking on a high-latency metric spike, viewing associated JSON logs in Loki, and jumping directly to the exact OpenTelemetry trace waterfall in Tempo.

---

## Project Structure

```
epricing-service/
├── src/
│   ├── main/
│   │   ├── java/com/bank/epricing/
│   │   │   ├── config/              # OpenTelemetry & Web MVC configuration
│   │   │   ├── controller/          # REST Controllers (PricingController, HealthController)
│   │   │   ├── dto/                 # Request & Response DTOs
│   │   │   ├── entity/              # JPA Entities (PricingRequest, PricingAuditLog)
│   │   │   ├── exception/           # Domain exceptions & GlobalExceptionHandler
│   │   │   ├── logging/             # MDCFilter & StructuredLogger
│   │   │   ├── metrics/             # Custom Micrometer PricingMetrics
│   │   │   ├── repository/          # Spring Data JPA Repositories
│   │   │   ├── service/             # Business Logic & Audit Services
│   │   │   └── util/                # Pure PricingCalculator math logic
│   │   └── resources/
│   │       ├── application.yml      # Spring Boot application configuration
│   │       ├── logback-spring.xml   # JSON log appenders with MDC fields
│   │       └── db/migration/        # Sequential SQL schema & seed scripts (V1..V5)
│   └── test/
│       └── java/com/bank/epricing/
│           ├── controller/          # PricingControllerTest (7 MockMvc tests: 200, 201, 400, 404, 422)
│           ├── service/             # PricingServiceTest (4 Mockito tests)
│           └── util/                # PricingCalculatorTest (6 JUnit 5 tests)
├── docs/
│   ├── compliance/
│   │   └── COMPLIANCE_FIXES.md      # Learning guide: every banking compliance issue & fix
│   ├── security/
│   │   └── Security.md              # Security controls (implemented + remaining)
│   └── ...                          # Full docs index in docs/README.md
├── docker-compose.yml               # Complete Docker orchestration
├── pom.xml                          # Maven build dependencies
└── README.md                        # This file
```

---

## API Endpoints

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `POST` | `/api/v1/pricing` | Calculate interest rate & EMI, store request and result in DB |
| `GET` | `/api/v1/pricing` | View pricing history (optional `?customerId=CUST001234`) |
| `GET` | `/api/v1/pricing/{id}` | View detailed pricing result by request ID |
| `GET` | `/api/v1/health` | Custom application & database health endpoint |
| `GET` | `http://localhost:8081/actuator/prometheus` | Prometheus metrics scrape endpoint |

### Example Request (`POST /api/v1/pricing`):
```json
{
  "customer_id": "CUST001234",
  "product_type": "HOME_LOAN",
  "loan_amount": 5000000.00,
  "loan_tenure_months": 240,
  "credit_score": 780,
  "annual_income": 2400000.00,
  "loan_purpose": "Purchase of residential apartment"
}
```

### Example Response (`201 Created`):
```json
{
  "request_id": 1,
  "customer_id": "CUST001234",
  "product_type": "HOME_LOAN",
  "loan_amount": 5000000.00,
  "loan_tenure_months": 240,
  "interest_rate_pa": 8.00,
  "emi_amount": 41822.00,
  "total_payable_amount": 10037280.00,
  "total_interest_payable": 5037280.00,
  "status": "CALCULATED",
  "message": "Pricing calculated successfully. Interest rate: 8.00% p.a. EMI: ₹41822.00/month",
  "trace_id": "abc123def456",
  "processing_time_ms": 142,
  "risk_category": "LOW_MEDIUM"
}
```

---

## Quickstart Guide

### 1. Build and Test Locally
```bash
# Compile and run unit tests
mvn clean test

# Run application locally
mvn spring-boot:run
```

### 2. Run Complete Monitoring Stack via Docker Compose
```bash
docker-compose up --build -d
```

### 3. Access Monitoring Tools
- **Spring Boot API**: `http://localhost:8080/api/v1/health`
- **Prometheus Metrics**: `http://localhost:8081/actuator/prometheus`
- **Prometheus UI**: `http://localhost:9090`
- **Grafana Dashboard**: `http://localhost:3000` (User: `admin`, Password: `Bank@grafana123`)

---

## 🧠 Core Observability Concepts Explained

### 1. JVM Heap vs Non-Heap Memory
- **Heap Memory**: Dynamic RAM where Java creates objects (`PricingRequest`, `BigDecimal`). Garbage Collector automatically deletes unused objects. Heap > 85% causes GC pauses; 100% causes `OutOfMemoryError`.
- **Non-Heap Memory**: Native OS memory for JVM bytecode (Metaspace), thread stack frames, and JIT code cache.

### 2. Garbage Collection (GC) & STW Pauses
- **GC Pause**: "Stop-The-World" moment where Java freezes application threads to reclaim RAM. Monitored via `jvm_gc_pause_seconds_sum`.

### 3. Process CPU vs System CPU
- **Process CPU**: Percentage of CPU consumed strictly by `epricing-service` (0.0 to 1.0). Alert threshold set at 80% (0.80).
- **System CPU**: Total CPU load across all processes on the host system.

### 4. Latency Percentiles (P50, P95, P99)
- **P95 Latency**: 95% of customer requests respond faster than this time. RBI SLA compliance requires P95 < 2.0s.

### 5. HikariCP Database Connection Pool
- **Active Connections**: Queries currently executing in DB.
- **Pending Connections**: Requests queued waiting for a DB connection (`pending > 0` indicates DB bottleneck).

### 6. Centralized Logging & Tracing
- **Logback + Loki**: Structured JSON logs shipped by Promtail to Loki. Searchable by `traceId` or `level="ERROR"`.
- **OpenTelemetry**: Assigns a unique 128-bit `traceId` to every request to track end-to-end execution.
