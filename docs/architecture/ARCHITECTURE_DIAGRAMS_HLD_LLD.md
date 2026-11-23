# 🏛️ ePricing Service — Architecture Diagrams (HLD & LLD)

> **Document Type:** System & Component Architecture Specification  
> **Status:** Current & Production Hardened (v1.0.0-SNAPSHOT)  
> **Database:** YugabyteDB 2.20 (Distributed SQL — YSQL, Port 5433)  
> **Workflow Engine:** Temporal Server 1.24.1 (Durable State Machines, Port 7233)  
> **Event Streaming:** Apache Kafka 3.8.0 KRaft Mode (Ports 9092 / 29092)  
> **Observability:** OpenTelemetry (Traces) + Prometheus (Metrics) + Loki (Logs) + Tempo (Trace Storage) + Grafana (UI)

---

## 1. High-Level Diagram (HLD) — Complete 5-Tier Platform Architecture

The **High-Level Diagram** illustrates the entire distributed deployment ecosystem: the ingress tier, the containerized microservice, the durable workflow tier, the distributed database and event streaming tier, and the full-stack observability platform.

```mermaid
flowchart TB
    %% ================= CLIENT TIER =================
    subgraph Tier1 [" 🌐 1. Client & Ingress Tier "]
        direction LR
        Browser["🖥️ Banking Web / CRM Portal\n(CRMNXT / React)"]
        MobileApp["📱 Mobile Banking App\n(Loan Origination)"]
        ShowcaseUI["✨ Interactive Showcase\n(showcase/index.html)"]
        TrafficGen["🤖 Traffic Generator\n(cURL Load Simulator)"]
    end

    %% ================= APPLICATION TIER =================
    subgraph Tier2 [" ⚙️ 2. Microservice Application Tier (Spring Boot 3.3.4 / Java 21) "]
        direction TB
        subgraph Ports [" Network Endpoints "]
            BizPort["Port 8080 : /api/v1/pricing\n& /api/v1/workflow/pricing\n(Business API)"]
            MgmtPort["Port 8089 (host) / 8081 (container)\n: /actuator/prometheus\n(Observability Port)"]
        end
        AppCore["epricing-service\n(Spring Boot Microservice Engine)"]
        TempWorker["Temporal Worker\n(PricingActivitiesImpl)"]
        KafkaProd["Kafka Producer\n(KafkaPricingEventProducer)"]
        LocalLogs["📁 /app/logs/*.log\n(JSON Structured Logs)"]

        AppCore --> TempWorker
        AppCore --> KafkaProd
        AppCore -.->|Writes logs| LocalLogs
    end

    %% ================= WORKFLOW ORCHESTRATION TIER =================
    subgraph Tier3 [" ⏳ 3. Durable Workflow Orchestration Tier "]
        TempServer["⚡ Temporal Server (:7233)\n(Durable State Machine Engine)"]
        TempUI["🖥️ Temporal Web UI (:8233)\n(Workflow Inspector)"]
        TempDB[("🐘 Temporal PostgreSQL (:5432)\nDatabase: temporal")]
        
        TempServer --- TempUI
        TempServer --> TempDB
    end

    %% ================= EVENT STREAMING & DATA TIER =================
    subgraph Tier4 [" 🗄️ 4. Event Streaming & Distributed Database Tier "]
        YugabyteDB[("🐘 YugabyteDB (YSQL :5433)\nDistributed SQL (PostgreSQL Wire-Compatible)\nDatabase: epricingdb\nTables: pricing_requests, pricing_audit_logs")]
        KafkaBroker["📨 Apache Kafka 3.8.0 (KRaft :9092/:29092)\nCluster ID / Node ID 1 (No ZooKeeper)\nTopic: loan-pricing-events (3 Partitions)"]
        Lakehouse[("🏛️ Analytics Lakehouse\n(BigQuery / Iceberg Star Schema)\nFacts: fact_pricing_decisions, fact_audit_events\nDims: dim_customer, dim_loan_product, dim_date")]

        KafkaBroker -.->|ETL/ELT Stream Ingestion| Lakehouse
        YugabyteDB -.->|CDC / Batch Ingestion| Lakehouse
    end

    %% ================= FULL-STACK OBSERVABILITY PLATFORM =================
    subgraph Tier5 [" 🔭 5. Full-Stack Observability Platform (LGTM + Prometheus) "]
        direction TB
        Promtail["🚚 Promtail (Log Tailer)"]
        OTelCol["🛰️ OpenTelemetry Collector (:4317/:4318)"]
        NodeExp["💻 Node Exporter (:9100)"]
        cAdvisor["🐳 cAdvisor (:8085)"]
        Prometheus["📈 Prometheus (:9090)"]
        Loki["📜 Grafana Loki (:3100)"]
        Tempo["🧭 Grafana Tempo (:3200)"]
        Alertmanager["🚨 Alertmanager (:9093)"]
        WebhookLog["🔔 Webhook Logger (:5001)"]
        Grafana["📊 Grafana Unified UI (:3000)\n(L1 Ops, L2 Complete, L3 Logs, L4 Infra)"]

        LocalLogs --> Promtail --> Loki
        OTelCol --> Tempo
        OTelCol --> Prometheus
        Prometheus --> Alertmanager --> WebhookLog
        Prometheus -.->|Scrapes| MgmtPort
        Prometheus -.->|Scrapes| NodeExp
        Prometheus -.->|Scrapes| cAdvisor
        Grafana --> Prometheus & Loki & Tempo & YugabyteDB
    end

    %% Flow Connections
    Browser & MobileApp & ShowcaseUI & TrafficGen --> BizPort --> AppCore
    AppCore -->|HikariCP YSQL :5433| YugabyteDB
    AppCore <-->|gRPC :7233| TempServer
    KafkaProd -->|Publish :29092| KafkaBroker
    AppCore -->|OTLP Traces :4318| OTelCol

    classDef client fill:#E1F5FE,stroke:#0288D1,stroke-width:2px,color:#01579B;
    classDef app fill:#E8F5E9,stroke:#2E7D32,stroke-width:2px,color:#1B5E20;
    classDef workflow fill:#FFFDE7,stroke:#FBC02D,stroke-width:2px,color:#F57F17;
    classDef data fill:#FFF3E0,stroke:#E65100,stroke-width:2px,color:#BF360C;
    classDef obs fill:#F3E5F5,stroke:#7B1FA2,stroke-width:2px,color:#4A148C;

    class Browser,MobileApp,ShowcaseUI,TrafficGen client;
    class AppCore,TempWorker,KafkaProd,LocalLogs,BizPort,MgmtPort app;
    class TempServer,TempUI,TempDB workflow;
    class YugabyteDB,KafkaBroker,Lakehouse data;
    class Promtail,OTelCol,NodeExp,cAdvisor,Prometheus,Loki,Tempo,Alertmanager,WebhookLog,Grafana obs;
```

---

## 2. Low-Level Diagram (LLD) — Component Architecture & Internals

The **Low-Level Component Diagram** details the internal modularization, interface abstractions, event serialization, and durable workflow wiring:

```mermaid
classDiagram
    direction TB

    %% ================= CONTROLLER TIER =================
    class PricingController {
        -PricingService pricingService
        +calculatePricing(PricingRequestDto) ResponseEntity
        +getPricingById(Long) ResponseEntity
        +getPricingHistory(String) ResponseEntity
    }

    class TemporalPricingController {
        -TemporalPricingService temporalPricingService
        +startWorkflow(PricingRequestDto) ResponseEntity
        +approveWorkflow(String) ResponseEntity
        +rejectWorkflow(String, String) ResponseEntity
        +getWorkflowStatus(String) ResponseEntity
        +getQuickWorkflowStatus(String) ResponseEntity
    }

    %% ================= WORKFLOW ORCHESTRATION =================
    class LoanPricingWorkflow {
        <<interface>>
        +processLoanPricing(PricingRequestDto, String, int) PricingWorkflowResult
        +approveQuote() void
        +rejectQuote(String) void
        +getStatus() String
        +getWorkflowResult() PricingWorkflowResult
    }

    class LoanPricingWorkflowImpl {
        -PricingActivities activities
        -String status
        -boolean approved
        -boolean rejected
        +processLoanPricing() PricingWorkflowResult
    }

    class PricingActivities {
        <<interface>>
        +calculateAndPersistPricing(PricingRequestDto, String) PricingResponseDto
        +recordQuoteApproval(String, String) void
        +recordQuoteRejection(String, String, String) void
        +recordQuoteExpiration(String, String) void
    }

    class PricingActivitiesImpl {
        -PricingService pricingService
        -PricingRepository pricingRepository
        -PricingAuditRepository auditRepository
        -PricingEventProducer pricingEventProducer
        +calculateAndPersistPricing() PricingResponseDto
        +recordQuoteApproval() void
        +recordQuoteRejection() void
        +recordQuoteExpiration() void
        -publishStatusEvent(PricingRequest, String) void
    }

    %% ================= BUSINESS SERVICE LAYER =================
    class PricingService {
        -PricingRepository pricingRepository
        -PricingCalculator pricingCalculator
        -PricingMetrics pricingMetrics
        -PricingAuditService auditService
        -StructuredLogger structuredLogger
        -Tracer tracer
        -PricingEventProducer pricingEventProducer
        +calculatePricing(PricingRequestDto, String) PricingResponseDto
    }

    class PricingAuditService {
        -PricingAuditRepository auditRepository
        +savePricingWithAudit(PricingRequest, String, String) PricingRequest
        +recordRejection(PricingRequestDto, String, String, String, String) void
    }

    %% ================= EVENT STREAMING LAYER =================
    class PricingEventProducer {
        <<interface>>
        +publishPricingEvent(LoanPricingEvent) CompletableFuture
        +isKafkaEnabled() boolean
        +getTopic() String
    }

    class KafkaPricingEventProducer {
        -KafkaOperations kafkaOperations
        -Tracer tracer
        -boolean kafkaEnabled
        -String topic
        +publishPricingEvent(LoanPricingEvent) CompletableFuture
    }

    class LoanPricingEvent {
        +Long requestId
        +String customerId
        +String productType
        +BigDecimal loanAmount
        +BigDecimal interestRate
        +BigDecimal emiAmount
        +Integer loanTenureMonths
        +BigDecimal totalPayableAmount
        +BigDecimal totalInterestPayable
        +String riskCategory
        +String status
        +LocalDateTime timestamp
        +String traceId
    }

    class KafkaProducerConfig {
        -KafkaProperties kafkaProperties
        +producerFactory() ProducerFactory
        +kafkaTemplate() KafkaTemplate
    }

    %% ================= RELATIONSHIPS =================
    PricingController --> PricingService : invokes
    TemporalPricingController --> LoanPricingWorkflow : starts & signals
    LoanPricingWorkflowImpl ..|> LoanPricingWorkflow : implements
    LoanPricingWorkflowImpl --> PricingActivities : invokes activities
    PricingActivitiesImpl ..|> PricingActivities : implements
    PricingActivitiesImpl --> PricingService : delegates calculation
    PricingActivitiesImpl --> PricingEventProducer : publishes state events
    PricingService --> PricingEventProducer : publishes CALCULATED event
    KafkaPricingEventProducer ..|> PricingEventProducer : implements
    KafkaPricingEventProducer --> KafkaProducerConfig : configured by
    KafkaPricingEventProducer ..> LoanPricingEvent : serializes & publishes
    PricingActivitiesImpl ..> LoanPricingEvent : constructs event
```

---

## 3. Low-Level Sequence Diagram — End-to-End Request & Event Lifecycle

```mermaid
sequenceDiagram
    autonumber
    actor Client as 🌐 Client (Browser / App / CRM)
    participant Filter as 🛡️ MDCFilter
    participant Ctrl as 🎮 PricingController
    participant Svc as ⚙️ PricingService
    participant Calc as 🧮 PricingCalculator
    participant Repo as 💾 PricingRepository
    participant Audit as 📜 PricingAuditService
    participant Kafka as 📨 KafkaPricingEventProducer
    participant Broker as 📬 Apache Kafka Broker
    participant Metrics as 📊 PricingMetrics
    participant DB as 🐘 YugabyteDB (YSQL)

    Client->>Filter: POST /api/v1/pricing (Payload + X-Customer-ID)
    activate Filter
    Note over Filter: 1. Generate X-Request-ID<br/>2. Mask Customer ID (CUST****1234)<br/>3. Bind to SLF4J MDC
    
    Filter->>Ctrl: Dispatch to calculatePricing()
    activate Ctrl
    Note over Ctrl: Validate DTO (@Valid constraints)
    
    Ctrl->>Svc: calculatePricing(PricingRequestDto)
    activate Svc
    
    Note over Svc: Open child span: "calculatePricing"
    Svc->>Metrics: incrementActiveRequests()
    Svc->>Metrics: recordPricingRequestReceived()

    %% Eligibility Check
    rect rgb(240, 248, 255)
        Note over Svc,Calc: Step A: Eligibility & Validation
        Svc->>Calc: validateEligibility(creditScore, income, amount)
        activate Calc
        Calc-->>Svc: Eligibility OK (FOIR < 50%, Score >= 650)
        deactivate Calc
    end

    %% Math Engine
    rect rgb(245, 255, 250)
        Note over Svc,Calc: Step B: Financial Computations
        Svc->>Calc: calculateInterestRate(productType, creditScore)
        activate Calc
        Calc-->>Svc: Rate: 8.00%
        deactivate Calc
        
        Svc->>Calc: calculateEmi(loanAmount, 8.00%, tenure)
        activate Calc
        Calc-->>Svc: EMI: ₹41,822.00
        deactivate Calc
    end

    %% Persistence
    rect rgb(255, 250, 240)
        Note over Svc,DB: Step C: Database Persistence (@Transactional)
        Svc->>Audit: savePricingWithAudit(PricingRequest)
        activate Audit
        Audit->>Repo: save(PricingRequest)
        activate Repo
        Repo->>DB: INSERT INTO pricing_requests (status='CALCULATED')
        DB-->>Repo: Saved Entity (id=101)
        Repo-->>Audit: Saved PricingRequest
        deactivate Repo
        Audit->>DB: INSERT INTO pricing_audit_logs (action='PRICING_CALCULATED')
        DB-->>Audit: OK
        Audit-->>Svc: Audit Recorded
        deactivate Audit
    end

    %% Kafka Event Streaming
    rect rgb(240, 255, 240)
        Note over Svc,Broker: Step D: Asynchronous Domain Event Streaming
        Svc->>Kafka: publishPricingEvent(LoanPricingEvent)
        activate Kafka
        Note over Kafka: Injects W3C traceparent header | max.block.ms=3000
        Kafka->>Broker: send(ProducerRecord: key=customerId)
        Broker-->>Kafka: Ack (partition 0, offset 42)
        Kafka-->>Svc: CompletableFuture<SendResult>
        deactivate Kafka
    end

    %% Telemetry Recording
    rect rgb(255, 245, 245)
        Note over Svc,Metrics: Step E: Metrics & Tracing Closure
        Svc->>Metrics: recordPricingSuccess()
        Svc->>Metrics: decrementActiveRequests()
        Svc->>Metrics: recordCalculationDuration(timeTaken)
    end

    Note over Svc: Close child span: "calculatePricing"
    Svc-->>Ctrl: Return PricingResponseDto
    deactivate Svc

    Ctrl-->>Filter: 201 Created (JSON Response Body)
    deactivate Ctrl

    Note over Filter: 1. Log completion with durationMs<br/>2. Clear MDC (MDC.clear())
    Filter-->>Client: HTTP 201 Created + Header [X-Request-ID]
    deactivate Filter
```

---

## 3.1 Temporal Durable Workflow Sequence Diagram — 7-Day Rate Lock Data Flow

```mermaid
sequenceDiagram
    autonumber
    actor Borrower as 🌐 Borrower / CRM
    participant Ctrl as 🎮 TemporalPricingController
    participant Svc as ⚙️ TemporalPricingService
    participant Server as ⚡ Temporal Server (:7233)
    participant Worker as ⏳ Temporal Worker (PricingActivitiesImpl)
    participant DB as 🐘 YugabyteDB (YSQL :5433)
    participant Kafka as 📨 Kafka Broker (:29092)

    Borrower->>Ctrl: POST /api/v1/workflow/pricing/start (Payload)
    Ctrl->>Svc: startPricingWorkflow(PricingRequestDto)
    Svc->>Server: Start Workflow: LoanPricingWorkflow (ID: LP-REQ-1042)
    Server-->>Svc: Workflow Started
    Svc-->>Ctrl: Return workflowId
    Ctrl-->>Borrower: HTTP 202 Accepted { "workflowId": "LP-REQ-1042" }

    Server->>Worker: Schedule Activity Task: calculateAndPersistPricing()
    activate Worker
    Worker->>DB: Atomically persist pricing_requests (CALCULATED) & audit_logs
    Worker->>Kafka: publishPricingEvent(LoanPricingEvent: CALCULATED)
    Worker-->>Server: Return PricingResponseDto (8.00%, EMI ₹41,822, 7-day validity)
    deactivate Worker

    Server->>Server: Durable Timer: Workflow.await(7 days OR approval/rejection signal)
    Note over Server: JVM threads released! Workflow state stored durably in PostgreSQL.

    alt Borrower Approves Rate Offer
        Borrower->>Ctrl: POST /api/v1/workflow/pricing/LP-REQ-1042/approve
        Ctrl->>Server: Signal: approveQuote()
        Server->>Worker: Activity Task: recordQuoteApproval()
        activate Worker
        Worker->>DB: UPDATE pricing_requests SET status = 'APPROVED'
        Worker->>DB: INSERT pricing_audit_logs (action = 'RATE_APPROVED')
        Worker->>Kafka: publishStatusEvent(LoanPricingEvent: APPROVED)
        Worker-->>Server: Activity Complete
        deactivate Worker
    else Borrower Declines Offer
        Borrower->>Ctrl: POST /api/v1/workflow/pricing/LP-REQ-1042/reject?reason=CompetitorOffer
        Ctrl->>Server: Signal: rejectQuote("CompetitorOffer")
        Server->>Worker: Activity Task: recordQuoteRejection()
        activate Worker
        Worker->>DB: UPDATE pricing_requests SET status = 'REJECTED'
        Worker->>DB: INSERT pricing_audit_logs (action = 'RATE_REJECTED')
        Worker->>Kafka: publishStatusEvent(LoanPricingEvent: REJECTED)
        Worker-->>Server: Activity Complete
        deactivate Worker
    else 7-Day Timer Elapses
        Server->>Worker: Activity Task: recordQuoteExpiration()
        activate Worker
        Worker->>DB: UPDATE pricing_requests SET status = 'EXPIRED'
        Worker->>DB: INSERT pricing_audit_logs (action = 'RATE_EXPIRED')
        Worker->>Kafka: publishStatusEvent(LoanPricingEvent: EXPIRED)
        Worker-->>Server: Activity Complete
        deactivate Worker
    end
```

---

## 3.2 Analytics Lakehouse & Streaming Pipeline Data Flow Diagram

```mermaid
flowchart LR
    subgraph Sources [" Operational Sources "]
        direction TB
        Kafka["Kafka Topic\nloan-pricing-events\n(Key=customerId)"]
        YSQL["YugabyteDB\npricing_requests\npricing_audit_logs"]
    end

    subgraph Streaming [" Ingestion Engines "]
        direction TB
        Beam["Apache Beam / Dataflow\n(Streaming Runner)"]
        Debezium["Debezium CDC Connector\n(Change Data Capture)"]
    end

    subgraph Lakehouse [" Enterprise Analytics Lakehouse (BigQuery / Iceberg) "]
        direction TB
        Bronze["1. Bronze Layer\nRaw Event Payloads & CDC Changelogs"]
        Silver["2. Silver Layer\n- PII Masking: CUST****1234\n- Salted HMAC-SHA256 Tokens\n- UTC Temporal Standardization"]
        
        subgraph Gold [" 3. Gold Layer: Kimball Star Schema "]
            direction TB
            FactCalc["fact_pricing_decisions\n(EMIs, rates, amounts, FOIR, trace_id)"]
            FactAudit["fact_audit_events\n(actions, outcomes, latencies, trace_id)"]
            DimCust["dim_customer (SCD 2)\n(credit tiers, income brackets)"]
            DimProd["dim_loan_product\n(risk multipliers, base rates)"]
            DimDate["dim_date\n(fiscal quarters, banking days)"]
        end
    end

    Kafka --> Beam --> Bronze
    YSQL --> Debezium --> Bronze
    Bronze --> Silver --> Gold
    DimCust -.->|1:N| FactCalc
    DimProd -.->|1:N| FactCalc
    DimDate -.->|1:N| FactCalc
    FactCalc -.->|1:N| FactAudit
```

---

## 4. Database Schema (ERD) & Entity Architecture

```mermaid
erDiagram
    PRICING_REQUESTS ||--o{ PRICING_AUDIT_LOGS : "tracks regulatory audit entries for"

    PRICING_REQUESTS {
        BIGSERIAL id PK "Primary Key (Generated)"
        VARCHAR(50) customer_id "Indexed, Masked in Logs"
        VARCHAR(50) product_type "HOME_LOAN, PERSONAL_LOAN, etc."
        DECIMAL(15_2) loan_amount "Requested Principal"
        INT loan_tenure_months "Tenure in months"
        INT credit_score "CIBIL / Experian score (300-900)"
        DECIMAL(15_2) annual_income "Used for FOIR calculation"
        VARCHAR(200) loan_purpose "Purpose of loan"
        DECIMAL(5_2) calculated_rate "Final computed interest %"
        DECIMAL(15_2) emi_amount "Monthly payment"
        DECIMAL(15_2) total_payable_amount "Principal + Total Interest"
        DECIMAL(15_2) total_interest_payable "Total interest paid"
        VARCHAR(30) risk_category "LOW, MEDIUM, HIGH"
        VARCHAR(20) status "CALCULATED, APPROVED, REJECTED, EXPIRED"
        VARCHAR(2000) error_message "Failure reason in Grafana"
        BIGINT processing_time_ms "Calculation execution time"
        VARCHAR(50) request_ip "Client IP (Proxy Aware)"
        VARCHAR(64) trace_id "Indexed OTel Trace ID"
        TIMESTAMP created_at "Created timestamp"
        TIMESTAMP updated_at "Updated timestamp"
    }

    PRICING_AUDIT_LOGS {
        BIGSERIAL id PK "Primary Key (Generated)"
        BIGINT pricing_request_id FK "Soft FK to pricing_requests.id"
        VARCHAR(50) customer_id "Customer ID"
        VARCHAR(50) action "PRICING_CALCULATED, RATE_APPROVED, etc."
        VARCHAR(500) description "Audit justification details"
        VARCHAR(100) performed_by "SYSTEM / Operator"
        VARCHAR(20) outcome "SUCCESS / REJECTED"
        VARCHAR(50) request_ip "Client IP"
        VARCHAR(64) trace_id "OTel Trace ID"
        BIGINT duration_ms "Operation latency"
        TIMESTAMP created_at "Immutable audit timestamp"
    }
```

---

## Summary of Key Design Highlights for Interviews & Presentations

| Aspect | Implementation Details |
|---|---|
| **Architectural Style** | 5-Tier Event-Driven Distributed Microservice with durable state machine orchestration. |
| **Durable Workflows** | Temporal Server orchestrating 7-day rate-lock quotes with asynchronous customer approval/rejection signals. |
| **Event Streaming** | Apache Kafka 3.8.0 in ZooKeeper-less KRaft mode, publishing `LoanPricingEvent` with fail-fast (3000ms) producer protection. |
| **Concurrency & Transactions** | `@Transactional` single-phase atomic commit; HikariCP connection pooling tuned for YugabyteDB distributed SQL. |
| **Distributed Tracing** | Full W3C `traceparent` context propagation across HTTP controllers, internal services, and Kafka producer records. |
| **Observability Pillars** | Correlated via `traceId` across **Prometheus** (Metrics), **Loki** (Logs), **Tempo** (Traces), and **Grafana** (Unified UI). |
| **Banking Compliance** | Synchronous immutable audit logging, PII masking across all log tiers, and least-privilege `grafana_reader` database role. |
