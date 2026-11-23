# Bank — ePricing Distributed Microservice & Observability Platform

An enterprise-grade, production-style Spring Boot 3.3.4 banking microservice (Java 21 LTS) demonstrating real-world retail loan pricing calculations, financial concurrency controls, **Temporal durable workflow orchestration**, **Apache Kafka KRaft event streaming**, **YugabyteDB distributed SQL**, and a full-stack **Observability Platform (LGTM + Prometheus)** across **17 orchestrated platform containers**.

---

## Table of Contents
1. [Platform Overview & Core Capabilities](#platform-overview-core-capabilities)
2. [5-Tier Enterprise Architecture](#5-tier-enterprise-architecture)
3. [End-to-End Data Flow](#end-to-end-data-flow)
4. [Banking ePricing Application Domain](#banking-epricing-application-domain)
5. [Event Streaming & Messaging Tier (Kafka KRaft)](#event-streaming-messaging-tier-kafka-kraft)
6. [Durable Workflow Orchestration (Temporal)](#durable-workflow-orchestration-temporal)
7. [Interactive Architectural UI Showcase](#interactive-architectural-ui-showcase)
8. [Full-Stack Observability Platform](#full-stack-observability-platform)
9. [Project Structure](#project-structure)
10. [REST API Reference](#rest-api-reference)
11. [Quickstart Guide](#quickstart-guide)
12. [Master Documentation Directory](#master-documentation-directory)

---

<a id="platform-overview-core-capabilities"></a><a id="platform-overview--core-capabilities"></a>
## Platform Overview & Core Capabilities

This project implements a commercial retail banking **ePricing (Electronic Pricing) Engine**. In modern digital banking, ePricing applications evaluate borrower creditworthiness, verify regulatory debt-to-income limits (FOIR), calculate risk-adjusted interest rates and reducing-balance EMIs, manage multi-day rate-lock guarantees, and stream canonical domain events into downstream analytical lakehouses.

### Key Architectural Pillars:

1. **Durable Workflow Orchestration (Temporal 1.24.1)**:
   - Guarantees 7-day quote rate locks via deterministic state machines without holding threads in JVM memory.
   - Handles asynchronous borrower decisions (`approveQuote`, `rejectQuote`) and automatic quote expiration.
   - Inspectable via the Temporal Web UI (`http://localhost:8233`).
   > See [`docs/workflows/TemporalWorkflows.md`](docs/workflows/TemporalWorkflows.md).

2. **Event Streaming & Fail-Fast Messaging (Apache Kafka 3.8.0 KRaft)**:
   - Single-node Kafka broker operating without ZooKeeper on ports `9092` (host) and `29092` (internal Docker).
   - Publishes canonical `LoanPricingEvent` domain events on topic `loan-pricing-events` partitioned by `customerId`.
   - **Fail-Fast Producer Resilience (`max.block.ms: 3000`)**: Broker unreachability times out within 3 seconds, logging errors without failing core database transactions or blocking Tomcat worker threads.
   - W3C `traceparent` OpenTelemetry header injection across asynchronous message boundaries.
   > See [`docs/messaging/KafkaKRaftBroker.md`](docs/messaging/KafkaKRaftBroker.md), [`LoanPricingEventStreaming.md`](docs/messaging/LoanPricingEventStreaming.md), and [`ProducerFailFast.md`](docs/messaging/ProducerFailFast.md).

3. **Distributed Enterprise SQL (YugabyteDB 2.20 / PostgreSQL)**:
   - Multi-master distributed ACID database operating on YSQL port `5433`.
   - Atomic persistence via `@Transactional` bounds; sequential DDL migrations (V1..V6).
   - Dedicated read-replica role isolation (`grafana_reader`).
   > See [`docs/database/DatabaseDesign.md`](docs/database/DatabaseDesign.md).

4. **Analytics Star-Schema Lakehouse Integration**:
   - Comprehensive dimensional Kimball star-schema mapping OLTP records and Kafka streams into BigQuery / Apache Iceberg Lakehouse fact and dimension tables.
   > See [`docs/mapping-manifesto.md`](docs/mapping-manifesto.md).

5. **Full-Stack Observability Platform (LGTM + Prometheus)**:
   - Correlated Metrics (Prometheus), Logs (Loki + Promtail), Traces (OpenTelemetry + Tempo), and Alerting (Alertmanager + Webhook Logger).
   - Pre-provisioned Grafana dashboards (L1 Operations, L2 Observability, L3 Log Analytics, L4 Container/Pod/Node Infrastructure).

6. **Interactive Architectural Showcase**:
   - Browser-based interactive sandbox in `showcase/` (`index.html`, `glassmorphism.html`) demonstrating real-time calculations, Temporal state transitions, Kafka event payloads, and glassmorphic UI tokens.
   > See [`docs/showcase/InteractiveShowcase.md`](docs/showcase/InteractiveShowcase.md).

7. **Comprehensive Verification Suite (17 Test Classes, 7 Categories)**:
   - Full test suite combining pure unit tests, `@SpringBootTest` context bootstrap validations (`ApplicationContextBootstrapTest`, `KafkaContextBootstrapTest`), H2 migration tests, in-memory Temporal workflow tests, and live Kafka integration tests.
   > See [`docs/testing/TestStrategy.md`](docs/testing/TestStrategy.md).

---

## 5-Tier Enterprise Architecture

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                 1. CLIENT & INGRESS TIER                               │
│     Postman │ React Banking Portal │ Mobile App │ Interactive Showcase (showcase/)     │
└───────────────────────────────────────────┬────────────────────────────────────────────┘
                                            │ HTTP :8080 (/api/v1/*)
                                            ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                 2. APPLICATION TIER (Spring Boot 3.3.4 / Java 21 LTS)                  │
│                                                                                        │
│   [MDCFilter] ──► [OTel Tracing Bridge] ──► [PricingController & TemporalController]   │
│                                                     │                                  │
│   ┌─────────────────────────────────────────────────┴──────────────────────────────┐   │
│   │ PricingService ◄──► PricingCalculator (EMI math) ◄──► PricingActivitiesImpl   │   │
│   │ KafkaPricingEventProducer (max.block.ms: 3000, W3C traceparent injection)      │   │
│   │ PricingMetrics (Micrometer) & StructuredLogger (Sanitized PII: CUST****1234)   │   │
│   └───────┬─────────────────────────┬──────────────────────┬───────────────────────┘   │
│           │                         │                      │                           │
│   Management Port 8089 (host)       │                      │                           │
│   /actuator/prometheus              │                      │                           │
└───┬─────────────────────────────────┼──────────────────────┼───────────────────────────┘
    │ YSQL (:5433)                    │ gRPC (:7233)         │ Kafka (:29092)
    ▼                                 ▼                      ▼
┌───────────────────────┐   ┌──────────────────┐   ┌─────────────────────────────────────┐
│ 4. DISTRIBUTED SQL    │   │ 3. WORKFLOW TIER │   │ 4. EVENT STREAMING BROKER           │
│ YugabyteDB (:5433)    │   │ Temporal Engine  │   │ Apache Kafka 3.8.0 (KRaft :9092)    │
│ epricingdb            │   │ (:7233)          │   │ Topic: loan-pricing-events (3 Part.)│
│ - pricing_requests    │   │ Temporal DB      │   └──────────────────┬──────────────────┘
│ - pricing_audit_logs  │   │ (Postgres :5432) │                      │
└───────────┬───────────┘   │ Temporal Web UI  │                      │ Stream Ingestion
            │               │ (:8233)          │                      ▼
            │ CDC Batch     └──────────────────┘          ┌──────────────────────────────┐
            └─────────────────────────┬──────────────────►│ Analytics Lakehouse          │
                                      │                   │ (BigQuery / Iceberg Star)    │
                                      │                   └──────────────────────────────┘
                                      │ OTLP HTTP (:4318)
                                      ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        5. FULL-STACK OBSERVABILITY PLATFORM                            │
│                                                                                        │
│   Prometheus (:9090) ◄── Scrapes /actuator/prometheus (Port 8089)                      │
│   Promtail           ──► Reads /app/logs/*.log ──► Grafana Loki (:3100)                │
│   OTel Collector     ──► Forwards distributed traces ──► Grafana Tempo (:3200)         │
│   Alertmanager       ──► Routes firing alert rules ──► Webhook Logger (:5001)          │
│                                                                                        │
│   Grafana Unified UI (:3000) ──► Single pane of glass for Metrics, Logs, Traces & DB  │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

<a id="end-to-end-data-flow"></a>
## End-to-End Data Flow

The ePricing platform coordinates data across four discrete lifecycles: synchronous HTTP decisioning, durable workflow state machines, asynchronous Kafka event streaming with downstream Lakehouse dimensional modeling, and full-stack telemetry collection.

```mermaid
flowchart TD
    %% ================= 1. SYNCHRONOUS INGRESS & PERSISTENCE =================
    subgraph S1 [" 1. Synchronous Online Decisioning "]
        Client["Client / CRM / App"] -->|1. POST /api/v1/pricing\nX-Customer-ID: CUST001234\nX-Request-ID: REQ-1042| MDC["MDCFilter\n- Mask PII (CUST****1234)\n- Trace ID in MDC"]
        MDC -->|2. Deserialized PricingRequestDto| Ctrl["PricingController"]
        Ctrl -->|3. Validated Request| Svc["PricingService\n(Math & Orchestration)"]
        Svc -->|4. Business Rules & Math| Calc["PricingCalculator\n- Credit Score Bounds\n- FOIR ≤ 50% Rule\n- Reducing Balance EMI"]
        Svc -->|5. Atomically Persist Quote & Audit| AuditSvc["PricingAuditService\n@Transactional(REQUIRED)"]
        AuditSvc -->|JDBC HikariCP| YB[("YugabyteDB (YSQL :5433)\n- pricing_requests (CALCULATED)\n- pricing_audit_logs")]
        Svc -->|6. Async Fail-Fast (3s timeout)\nPost-Commit Dispatch| KProd["KafkaPricingEventProducer\n- Key: customerId\n- Header: traceparent\n- Topic: loan-pricing-events"]
        Ctrl -->|7. HTTP 201 Created| Client
    end

    %% ================= 2. DURABLE WORKFLOW ORCHESTRATION =================
    subgraph S2 [" 2. Durable Workflow State Machine "]
        Borrower["Borrower / CRM"] -->|Start Quote| TCtrl["TemporalPricingController\nPOST /api/v1/workflow/pricing/start"]
        TCtrl -->|Launch Workflow| TServer["Temporal Server (:7233)\nWorkflow: LoanPricingWorkflow"]
        TServer -->|Activity Task| TWorker["PricingActivitiesImpl\n- calculateAndPersistPricing()\n- Atomically saves to YugabyteDB\n- Publishes Kafka Event"]
        TServer -->|Durable Sleep 7 Days| TSleep["Durable State Timer\n(JVM thread released)"]
        Borrower -->|Approve / Reject Signal| TCtrl
        TCtrl -->|Send Signal| TServer
        TServer -->|Signal Trigger| TWorkerUpdate["PricingActivitiesImpl\n- Updates Status (APPROVED/REJECTED)\n- Publishes Kafka Status Event"]
        TWorker -->|Save Quote & Audit| YB
        TWorker -->|Publish CALCULATED Event| KTopic["Kafka Topic: loan-pricing-events\n(3 Partitions, Key=customerId)"]
        TWorkerUpdate -->|UPDATE status & INSERT audit| YB
        TWorkerUpdate -->|Publish Status Event| KTopic
    end

    %% ================= 3. DOWNSTREAM ANALYTICS LAKEHOUSE =================
    subgraph S3 [" 3. Downstream Analytics Lakehouse "]
        KProd -.->|Domain Events| KTopic
        YB -.->|CDC Stream| CDCPipe["Debezium / CDC Pipeline"]
        KTopic & CDCPipe -->|ETL / ELT Streaming| Lakehouse["Google Cloud BigQuery / Iceberg Lakehouse"]
        Lakehouse --> DimCust["dim_customer (SCD Type 2)\n- customer_id_masked\n- customer_hash_token (Salted SHA256)\n- credit_score_tier\n- income_bracket"]
        Lakehouse --> DimProd["dim_loan_product\n- product_key\n- risk_multiplier\n- base_rate"]
        Lakehouse --> DimDate["dim_date\n- date_key (YYYYMMDD)"]
        Lakehouse --> FactDecision["fact_pricing_decisions\n- loan_amount, calculated_rate_pa\n- emi_amount, foir_ratio\n- trace_id, status_code"]
        Lakehouse --> FactAudit["fact_audit_events\n- action_code, outcome_code\n- duration_ms, trace_id"]
    end

    %% ================= 4. TELEMETRY & OBSERVABILITY =================
    subgraph S4 [" 4. Telemetry & Observability Pipeline "]
        Svc -.->|Counters, Timers, Gauges| Actuator["Micrometer Actuator (:8081/8089)"]
        Actuator -.->|Pull every 15s| Prom["Prometheus (:9090)"]
        Svc -.->|Structured JSON Logs| LogFiles["/app/logs/epricing-service.log"]
        LogFiles -.->|Tails file| Promtail["Promtail"]
        Promtail -.->|Pushes logs| Loki["Grafana Loki (:3100)"]
        Svc -.->|W3C Spans OTLP HTTP| OTel["OTel Collector (:4318)"]
        OTel -.->|Pushes traces| Tempo["Grafana Tempo (:3200)"]
        Prom & Loki & Tempo --> Grafana["Grafana Unified UI (:3000)\nL1 Ops, L2 Observability, L3 Logs, L4 Infra"]
        Grafana -.->|Direct SQL Queries (grafana_reader)| YB
    end
```

### Data Flow Breakdown by Channel

| Flow Stage | Source Component | Target Component | Transport / Protocol | Key Invariants & Transformations |
|---|---|---|---|---|
| **Ingress Sanitization** | Client / Gateway | `MDCFilter` | HTTP/1.1 (JSON) | Extracts `X-Request-ID`, sanitizes `X-Customer-ID` to `CUST****1234`, binds W3C `traceId` into SLF4J MDC. |
| **Validation & Math** | `PricingController` | `PricingCalculator` | In-Memory (Java 21) | Enforces credit score bounds [300, 900], rejects FOIR > 50%, computes reducing-balance EMI with `BigDecimal` half-even rounding. |
| **Transactional OLTP** | `PricingAuditService` | YugabyteDB (`epricingdb`) | JDBC (HikariCP / YSQL :5433) | Single atomic `@Transactional` boundary saving `pricing_requests` and `pricing_audit_logs`; avoids HikariCP starvation; dedicated `grafana_reader` role. |
| **Domain Event Outbox** | `PricingService` / Activities | Apache Kafka (`epricing-kafka`) | Kafka Protocol (:29092) | Topic `loan-pricing-events`, key = `customerId` for partition ordering; post-commit dispatch; `max.block.ms: 3000` fail-fast timeout. |
| **Durable State Lock** | `TemporalPricingController` | Temporal Server | gRPC (:7233) | 7-day rate guarantee state machine; workers sleep durably without consuming memory; responds to signals (`approveQuote`/`rejectQuote`). |
| **Lakehouse Ingestion** | Kafka & YugabyteDB | BigQuery / Iceberg | Stream Ingestion (Apache Beam / Spark) | Bronze raw ingestion $\to$ Silver cleaning (salted HMAC-SHA256 tokens) $\to$ Gold Kimball dimensional star schema. |
| **Metrics Pipeline** | `epricing-service` | Prometheus | HTTP GET (:8089/actuator/prometheus) | Scraped every 15s; JVM memory, Hikari pool stats, calculation latency, and throughput counters. |
| **Log Analytics** | Logback Appender | Loki | Promtail File Tailer (:3100) | Formatted JSON logs written to `/app/logs/*.log`; PII masked; indexed by level, thread, and traceId. |
| **Distributed Traces** | OTel Tracer Bridge | Grafana Tempo | OTLP HTTP (:4318) | Propagates W3C `traceparent` through HTTP, internal service spans, and Kafka producer records; correlates logs to traces in Grafana. |

> For the in-depth step-by-step walkthrough, see **[Project Flow Guide (`docs/PROJECT_FLOW.md`)](docs/PROJECT_FLOW.md)** and the **[Enterprise Lakehouse Schema Mapping Manifesto (`docs/mapping-manifesto.md`)](docs/mapping-manifesto.md)**.

---

## Banking ePricing Application Domain

An **ePricing Engine** is a mission-critical digital banking system responsible for instant loan eligibility evaluation and custom rate quotation:

- **Base Benchmark Rate**: Treasury base benchmark (8.50% p.a.).
- **Product Risk Margin**: Margin reflecting product collateral risk (Home Loan: 1.0x, Auto Loan: 1.1x, Personal Loan: 1.5x, Education Loan: 1.05x).
- **Credit Score Adjustments**: CIBIL/Experian tier discounts (e.g. score ≥ 800 qualifies for -1.00% rate discount; score < 650 rejected).
- **Volume Discounts**: Loan amounts ≥ ₹50,00,000 receive an additional -0.25% relationship discount.
- **Reducing Balance EMI**: Exact mathematical amortization:
  $$\text{EMI} = P \times r \times \frac{(1+r)^n}{(1+r)^n - 1}$$
- **FOIR Safety Boundary**: Enforces that borrower monthly obligations do not exceed 50% of verifiable monthly income.

---

<a id="event-streaming-messaging-tier-kafka-kraft"></a><a id="event-streaming--messaging-tier-kafka-kraft"></a>
## Event Streaming & Messaging Tier (Kafka KRaft)

The messaging infrastructure uses **Apache Kafka 3.8.0** in ZooKeeper-less **KRaft mode**:
- **Topic**: `loan-pricing-events` (3 partitions, default auto-creation enabled).
- **Domain Event (`LoanPricingEvent`)**: Emitted upon `CALCULATED`, `APPROVED`, `REJECTED`, and `EXPIRED` events.
- **Producer Fail-Fast Protection**:
  ```yaml
  spring:
    kafka:
      producer:
        acks: all
        retries: 3
        properties:
          enable.idempotence: true
          spring.json.add.type.headers: false
          max.block.ms: 3000
  ```
- **Trace Context Propagation**: Injects W3C `traceparent` headers into Kafka records, maintaining distributed trace context into downstream analytical pipelines.
- **Error Decoupling**: Database commit and HTTP 201 responses succeed even if Kafka is down or partitioned.

---

## Durable Workflow Orchestration (Temporal)

Retail loan rate quotes require a guaranteed validity window (7 days):
1. **Initiate Workflow**: `POST /api/v1/workflow/pricing/start` starts `LoanPricingWorkflow` (returns HTTP 202 Accepted).
2. **Durable Sleep**: Workflow awaits customer decision (`approveQuote` or `rejectQuote`) or 7-day timer expiration without consuming JVM worker threads.
3. **Signal Resolution**:
   - **Approval**: Sets status `APPROVED`, writes audit log, emits `LoanPricingEvent` to Kafka.
   - **Rejection**: Sets status `REJECTED`, records reason, emits Kafka event.
   - **Expiration**: 7-day timer elapses; sets status `EXPIRED`, emits Kafka event.

---

## Interactive Architectural UI Showcase

The repository includes a standalone interactive showcase in `showcase/`:
- **Real-Time Calculator**: Dynamic loan amount, tenure, credit score, and product type sliders with live EMI charts.
- **Temporal Simulator**: Interactive state visualizer for 7-day rate-lock quotes, approval signals, and expirations.
- **Kafka Event Inspector**: Live JSON visualizer showing emitted `LoanPricingEvent` payloads.
- **Glassmorphic Design System**: Modern dark fintech aesthetic (`glassmorphism.html`).

**To launch locally**:
```powershell
Start-Process "showcase\index.html"
```
*Or serve via Python:* `python -m http.server 8000` → `http://localhost:8000/showcase/index.html`.

---

<a id="full-stack-observability-platform"></a>
## Full-Stack Observability Platform

The banking platform provides comprehensive telemetry across **Metrics, Logs, Traces, and Alerts** through a fully integrated open-source LGTM + Prometheus stack:

- **Metrics Collection (Prometheus 2.53.0 & Micrometer)**:
  - Application scrapes via dedicated management port `8089` (`http://localhost:8089/actuator/prometheus`).
  - Gathers host OS and hardware metrics via **Node Exporter** (`:9100`) and container working sets via **cAdvisor** (`:8085`).
  - Tracks custom business metrics: `pricing_requests_total`, `pricing_calculation_duration_seconds`, `pricing_requests_active`, and HikariCP connection pool saturation.

- **Log Aggregation & PII Hygiene (Grafana Loki 3.1.0 & Promtail)**:
  - **Promtail** tails `/app/logs/*.log`, parsing JSON-formatted structured logs emitted by Logback.
  - Strict PII masking via `StructuredLogger.maskCustomerId()` ensures all customer identifiers are sanitized (`CUST****1234`) before log persistence.
  - Seamless label-based indexing without full-text index overhead.

- **Distributed Tracing (OpenTelemetry 1.40.0 & Grafana Tempo 2.5.0)**:
  - OpenTelemetry Java SDK automatically instruments incoming HTTP requests and propagates W3C `traceparent` headers into Kafka `ProducerRecord` headers.
  - Spans are exported via OTLP HTTP (`:4318`) to the **OpenTelemetry Collector Contrib** (`:4317/:4318`), which routes them into **Grafana Tempo** (`:3200`).
  - Full TraceQL queryability enabling click-through log-to-trace waterfall visualization.

- **Alerting & Notification (Alertmanager 0.27.0 & Webhook Logger)**:
  - 7 production alerting rules monitoring P95 latency (> 500ms), 5xx error spikes (> 1%), database connection starvation, and broker unreachability.
  - Dispatches alerts through **Alertmanager** (`:9093`) to a mock webhook logger service (`:5001`).

- **Unified Operations Dashboards (Grafana 11.1.0)**:
  - Pre-provisioned dashboards accessible at `http://localhost:3000` (`admin` / `Bank@grafana123`):
    - **L1 Operations Dashboard**: High-level business KPI metrics, transaction throughput, and success rates.
    - **L2 Complete Observability Dashboard**: Correlated metrics, golden signals, and deep application state.
    - **L3 Log Analytics Dashboard**: Loki log stream viewer with real-time log severity filters.
    - **L4 Container & Node Infrastructure**: CPU, memory, network, and disk I/O metrics across all 17 containers.
  - **AI & Machine Learning Capabilities**:
    - **Prometheus Mathematical Forecasting**: PromQL linear regression (`predict_linear()`), exponential smoothing (`holt_winters()`), and dynamic Z-score anomaly bounds.
    - **Grafana LLM Plugin (`grafana-llm-app`)**: Conversational AI assistant, natural language PromQL/LogQL/TraceQL generation, and Tempo trace root-cause explanations.
    - **Private Banking Mode (Ollama)**: Zero-data-egress local LLM inference via `docker compose --profile ai up -d ollama`.
  > See [`docs/monitoring/AI_IN_PROMETHEUS_AND_GRAFANA.md`](docs/monitoring/AI_IN_PROMETHEUS_AND_GRAFANA.md) and [`docs/HANDS_ON_OBSERVABILITY_LAB.md`](docs/HANDS_ON_OBSERVABILITY_LAB.md).

---

## Project Structure

```
epricing-service/
├── src/
│   ├── main/
│   │   ├── java/com/bank/epricing/
│   │   │   ├── config/              # Kafka, OpenTelemetry, and Web MVC configuration
│   │   │   ├── controller/          # REST Controllers (PricingController, HealthController)
│   │   │   ├── dto/                 # Request & Response DTOs
│   │   │   ├── entity/              # JPA Entities (PricingRequest, PricingAuditLog)
│   │   │   ├── event/               # Domain Events (LoanPricingEvent)
│   │   │   ├── exception/           # Business Exceptions & GlobalExceptionHandler
│   │   │   ├── logging/             # MDCFilter & StructuredLogger (PII masking)
│   │   │   ├── metrics/             # Custom Micrometer PricingMetrics
│   │   │   ├── repository/          # Spring Data JPA Repositories
│   │   │   ├── service/             # PricingService & KafkaPricingEventProducer
│   │   │   ├── temporal/            # Temporal Workflows, Activities, and Controllers
│   │   │   └── util/                # Pure PricingCalculator math formulas
│   │   └── resources/
│   │       ├── application.yml      # Multi-tier application configuration
│   │       ├── logback-spring.xml   # Structured JSON log appender configuration
│   │       └── db/migration/        # Sequential DDL & seed migration scripts (V1..V6)
│   └── test/
│       └── java/com/bank/epricing/  # 17 Test Classes across 7 functional categories
├── showcase/                        # Interactive Architectural UI Showcase
│   ├── index.html                   # Interactive pricing & workflow sandbox
│   └── glassmorphism.html           # Design system component gallery
├── docs/                            # Comprehensive documentation suite
│   ├── messaging/                   # Kafka KRaft Broker, Event Streaming, Fail-Fast Producer
│   ├── showcase/                    # Interactive Showcase documentation
│   ├── workflows/                   # Temporal durable workflow guide
│   ├── docker/                      # Docker Compose & 17-container deep-dive
│   ├── testing/                     # Test Strategy & Verification Architecture
│   ├── architectural-audit.md       # Official Architectural Hardening Audit Report
│   ├── mapping-manifesto.md         # Analytics Star-Schema Mapping Manifesto
│   └── PRODUCTION_READINESS_AUDIT.md# Latent trap & production readiness audit
├── k8s/                             # Production Kubernetes manifests (00-07)
├── grafana/                         # Pre-configured Grafana Dashboards (L1..L4)
├── docker-compose.yml               # Complete 17-container orchestration
└── pom.xml                          # Maven build dependencies (Java 21 LTS)
```

---

## REST API Reference

### Synchronous Pricing API
| Method | Endpoint | Description |
|:---:|---|---|
| `POST` | `/api/v1/pricing` | Calculate interest rate & EMI, persist to DB, and publish `LoanPricingEvent` to Kafka. |
| `GET` | `/api/v1/pricing` | Retrieve recent calculations (optional `?customerId=CUST****1234`). |
| `GET` | `/api/v1/pricing/{id}` | Retrieve detailed pricing calculation record by ID. |
| `GET` | `/api/v1/health` | Application, database, and system health status probe. |
| `GET` | `http://localhost:8089/actuator/prometheus` | Prometheus metrics scrape endpoint (host port 8089). |

### Temporal Durable Workflow API
| Method | Endpoint | Description |
|:---:|---|---|
| `POST` | `/api/v1/workflow/pricing/start` | Start durable 7-day rate-lock quote workflow (returns HTTP 202 with `workflowId`). |
| `GET` | `/api/v1/workflow/pricing/{workflowId}` | Query complete workflow state snapshot and execution result. |
| `GET` | `/api/v1/workflow/pricing/{workflowId}/status` | Fast status query returning simple JSON `{ "workflowId": "...", "status": "..." }`. |
| `POST` | `/api/v1/workflow/pricing/{workflowId}/approve` | Send customer approval signal; sets status `APPROVED` and emits Kafka event. |
| `POST` | `/api/v1/workflow/pricing/{workflowId}/reject` | Send customer rejection signal; sets status `REJECTED` and emits Kafka event. |

---

## Quickstart Guide

### 1. Execute Automated Tests (PowerShell)
```powershell
mvn clean test
```
*Executes all 17 test classes across 7 categories, including `@SpringBootTest` context bootstrap validations, in-memory Temporal workflow tests, H2 migrations, and Kafka producer tests.*

### 2. Launch Complete Platform via Docker Compose (17 Containers)
```powershell
docker-compose up --build -d
```

### 3. Service Dashboard Directory
- **ePricing API**: `http://localhost:8080/api/v1/health`
- **Prometheus Metrics**: `http://localhost:8089/actuator/prometheus`
- **Prometheus UI**: `http://localhost:9090`
- **Grafana Dashboards**: `http://localhost:3000` (`admin` / `Bank@grafana123`)
- **Temporal Web UI**: `http://localhost:8233`
- **Apache Kafka Broker**: `localhost:9092`
- **YugabyteDB Admin Console**: `http://localhost:7000`

---

## Master Documentation Directory

- **[Production Readiness Audit](docs/PRODUCTION_READINESS_AUDIT.md)** — Exhaustive audit of latent bugs, race conditions, memory leaks, and production traps.
- **[Architectural Hardening Audit](docs/architectural-audit.md)** — Official architectural verification assessing 5 dimensions and 7 risk vulnerabilities.
- **[Lakehouse Star-Schema Mapping Manifesto](docs/mapping-manifesto.md)** — Dimensional star-schema mapping OLTP records & Kafka events into BigQuery / Iceberg.
- **[Kafka KRaft Broker Architecture](docs/messaging/KafkaKRaftBroker.md)** — Kafka 3.8.0 KRaft mode architecture, listener configuration, volumes, and runbook.
- **[Domain Event Streaming Specification](docs/messaging/LoanPricingEventStreaming.md)** — `LoanPricingEvent` JSON schema, field definitions, and consumer contract.
- **[Fail-Fast Producer Resilience](docs/messaging/ProducerFailFast.md)** — 3000ms fail-fast timeout, idempotency, decoupled error handling, and outbox pattern.
- **[Interactive UI Showcase Guide](docs/showcase/InteractiveShowcase.md)** — Guide for browser-based interactive pricing calculator and rate-lock simulator.
- **[Temporal Durable Workflows](docs/workflows/TemporalWorkflows.md)** — 7-day rate lock state machine, activity retry policies, and Kafka event triggers.
- **[Docker Containers Guide](docs/docker/CONTAINERS_GUIDE.md)** — Deep-dive into all 17 platform containers, port mappings, volumes, and healthchecks.
- **[Docker Compose Reference](docs/docker/DockerCompose.md)** — Orchestration reference for running the complete multi-tier platform in one command.
- **[Test Strategy & Verification](docs/testing/TestStrategy.md)** — Verification architecture across 17 test classes, 7 categories, and `@SpringBootTest` bootstrap suites.
- **[AI in Prometheus & Grafana](docs/monitoring/AI_IN_PROMETHEUS_AND_GRAFANA.md)** — Architectural blueprint for PromQL predictive regression, Grafana LLM App plugin, Alertmanager AIOps triage, and air-gapped banking compliance.
- **[Technical Documentation Hub](docs/README.md)** — Centralized technical documentation hub for the entire repository.
