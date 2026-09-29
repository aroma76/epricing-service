# 🏛️ ePricing Service — Architecture Diagrams (HLD & LLD)

> **Document Type:** System & Component Architecture Specification  
> **Status:** Current & Production Hardened (v1.0.0-SNAPSHOT)  
> **Database:** YugabyteDB (Distributed SQL — YSQL, Port 5433)  
> **Observability:** OpenTelemetry (Traces) + Prometheus (Metrics) + Loki (Logs) + Tempo (Trace Storage) + Grafana (UI)

---

## 1. High-Level Diagram (HLD) — System & Platform Architecture

The **High-Level Diagram** illustrates the entire distributed deployment ecosystem: the ingress tier, the containerized microservice, the distributed database layer, and the multi-component observability platform.

```mermaid
flowchart TB
    %% ================= CLIENT TIER =================
    subgraph Client_Tier [" 🌐 1. Client & Traffic Tier "]
        direction LR
        Browser["🖥️ Web / CRM Portal\n(e.g., CRMNXT / Angular)"]
        MobileApp["📱 Mobile Banking App\n(Loan Origination)"]
        TrafficBot["🤖 Traffic Generator\n(Simulated Customer Traffic)"]
    end

    %% ================= APPLICATION TIER =================
    subgraph App_Tier [" ⚙️ 2. Core Microservice (Spring Boot 3.3.4 / Java 21) "]
        direction TB
        subgraph Ports [" Ports Exposed "]
            BizPort["Port 8080 : /api/v1/pricing\n(Business API)"]
            MgmtPort["Port 8081 : /actuator\n(Prometheus / Health / Info)"]
        end
        AppInstance["epricing-service\n(Stateless Containerized Microservice)"]
        LogsDisk["📁 Local /app/logs/\n(Structured JSON Log Files)"]
        AppInstance -.->|Writes logs| LogsDisk
    end

    %% ================= DATABASE TIER =================
    subgraph Data_Tier [" 🗄️ 3. Distributed Database Tier "]
        YugabyteDB[("🐘 YugabyteDB (YSQL :5433)\nDistributed SQL (PostgreSQL Wire-Compatible)\nDatabase: epricingdb\nTables: pricing_requests, pricing_audit_logs")]
        YugaWeb["📊 Yugabyte Web UI (:15433)\n(Cluster Health & Tablet Status)"]
        YugabyteDB --- YugaWeb
    end

    %% ================= OBSERVABILITY PLATFORM =================
    subgraph Obs_Tier [" 🔭 4. Full-Stack Observability Platform "]
        direction TB
        
        %% Ingestion agents
        Promtail["🚚 Promtail\n(Tails /app/logs/*.log)"]
        OTelCol["🛰️ OpenTelemetry Collector\n(Port :4317 gRPC / :4318 HTTP)"]
        NodeExp["💻 Node Exporter (:9100)\n(Host CPU / RAM / Disk)"]
        cAdvisor["🐳 cAdvisor (:8082)\n(Container Resource Metrics)"]

        %% Storage Engines
        Prometheus["📈 Prometheus (:9090)\n(Time-Series Metrics DB)"]
        Loki["📜 Grafana Loki (:3100)\n(Indexed Log Storage)"]
        Tempo["🧭 Grafana Tempo (:3200)\n(Distributed Trace Storage)"]

        %% Alerting & Visualization
        Alertmanager["🚨 Alertmanager (:9093)\n(Deduplication & Routing)"]
        WebhookLog["🔔 Webhook Logger (:5001)\n(Mock On-Call Pager Receiver)"]
        Grafana["📊 Grafana Dashboard UI (:3000)\n(L1 Ops, L2 Logs, L3 Telemetry, L4 Infra)"]
    end

    %% ================= FLOW CONNECTIONS =================
    %% Client to App
    Browser -->|HTTP REST POST / GET| BizPort
    MobileApp -->|HTTP REST POST / GET| BizPort
    TrafficBot -->|Simulated Requests| BizPort
    BizPort --> AppInstance

    %% App to Database
    AppInstance -->|YSQL Connection Pool HikariCP :5433| YugabyteDB

    %% Telemetry Export
    AppInstance -->|OTLP Traces :4318| OTelCol
    OTelCol -->|Trace Export| Tempo
    LogsDisk -->|Tail & Ship| Promtail
    Promtail -->|Push Log Streams| Loki

    %% Metrics Scraping
    Prometheus -->|Scrape :8081/actuator/prometheus every 15s| MgmtPort
    Prometheus -->|Scrape :9100/metrics| NodeExp
    Prometheus -->|Scrape :8082/metrics| cAdvisor

    %% Alerting
    Prometheus -->|Alert Rules Fire| Alertmanager
    Alertmanager -->|Webhook POST /alerts| WebhookLog

    %% Grafana Data Sources
    Grafana -->|PromQL| Prometheus
    Grafana -->|LogQL| Loki
    Grafana -->|TraceQL| Tempo
    Grafana -->|SQL Queries| YugabyteDB

    %% Styling
    classDef client fill:#E1F5FE,stroke:#0288D1,stroke-width:2px,color:#01579B;
    classDef app fill:#E8F5E9,stroke:#2E7D32,stroke-width:2px,color:#1B5E20;
    classDef db fill:#FFF3E0,stroke:#E65100,stroke-width:2px,color:#BF360C;
    classDef obs fill:#F3E5F5,stroke:#7B1FA2,stroke-width:2px,color:#4A148C;

    class Browser,MobileApp,TrafficBot client;
    class AppInstance,BizPort,MgmtPort,LogsDisk app;
    class YugabyteDB,YugaWeb db;
    class Promtail,OTelCol,NodeExp,cAdvisor,Prometheus,Loki,Tempo,Alertmanager,WebhookLog,Grafana obs;
```

---

## 2. Low-Level Diagram (LLD) — Component Architecture & Internals

The **Low-Level Component Diagram** details the internal Spring Boot modularization, class dependencies, design patterns, and cross-cutting concerns (MDC logging, Micrometer metrics, and OpenTelemetry spans).

```mermaid
classDiagram
    direction TB

    %% ================= INGRESS LAYER =================
    class MDCFilter {
        +doFilterInternal(request, response, chain)
        -extractOrGenerateRequestId()
        -maskCustomerId(customerId)
        -populateMDC()
        -clearMDC()
    }

    class PricingController {
        -PricingService pricingService
        +calculatePricing(PricingRequestDto) ResponseEntity
        +getPricingById(Long id) ResponseEntity
        +getPricingHistory(String customerId) ResponseEntity
    }

    class MetricsDemoController {
        -MeterRegistry meterRegistry
        +simulateSlow()
        +simulateError()
        +generateLoad()
        +traceDemo()
    }

    class GlobalExceptionHandler {
        +handleValidationExceptions(MethodArgumentNotValidException)
        +handlePricingException(PricingException)
        +handleGeneralException(Exception)
    }

    %% ================= SERVICE LAYER =================
    class PricingService {
        -PricingRepository pricingRepository
        -PricingCalculator pricingCalculator
        -PricingMetrics pricingMetrics
        -PricingAuditService auditService
        -StructuredLogger structuredLogger
        -Tracer tracer
        +calculatePricing(PricingRequestDto) PricingResponseDto
        +getPricingById(Long) PricingResponseDto
        +findByCustomerId(String) List~PricingResponseDto~
    }

    class PricingAuditService {
        -PricingAuditRepository auditRepository
        +recordSuccess(PricingRequest, traceId, ip)
        +recordRejection(PricingRequestDto, reason, traceId, ip)
    }

    %% ================= DOMAIN & UTILITY LAYER =================
    class PricingCalculator {
        -BASE_RATE: 8.50%
        -MAX_FOIR: 50.00%
        +validateEligibility(creditScore, annualIncome, loanAmount, tenure)
        +calculateInterestRate(productType, creditScore) BigDecimal
        +calculateEmi(principal, annualRate, tenureMonths) BigDecimal
        +calculateTotalPayable(emi, tenureMonths) BigDecimal
    }

    class PricingRequestDto {
        +String customerId
        +ProductType productType
        +BigDecimal loanAmount
        +Integer tenureMonths
        +Integer creditScore
        +BigDecimal annualIncome
    }

    class PricingResponseDto {
        +Long pricingId
        +BigDecimal calculatedRate
        +BigDecimal emiAmount
        +BigDecimal totalPayable
        +String riskCategory
        +String status
        +String traceId
    }

    %% ================= DATA LAYER =================
    class PricingRequest {
        <<Entity>>
        +Long id
        +String customerId
        +BigDecimal calculatedRate
        +BigDecimal emiAmount
        +String status
        +String traceId
        +LocalDateTime createdAt
    }

    class PricingAuditLog {
        <<Entity>>
        +Long id
        +Long pricingRequestId
        +String action
        +String outcome
        +String traceId
        +LocalDateTime createdAt
    }

    class PricingRepository {
        <<Repository>>
        +findByCustomerId(String) List
        +findById(Long) Optional
    }

    class PricingAuditRepository {
        <<Repository>>
        +findByCustomerId(String) List
    }

    %% ================= OBSERVABILITY INFRA =================
    class PricingMetrics {
        -MeterRegistry registry
        +recordPricingRequestReceived()
        +recordPricingSuccess()
        +recordPricingFailure()
        +incrementActiveRequests()
        +decrementActiveRequests()
        +recordCalculationDuration(Duration)
    }

    class StructuredLogger {
        -Logger log
        +logPricingStarted()
        +logPricingCompleted()
        +logValidationFailure()
        +logPricingError()
    }

    %% Relationships
    MDCFilter --> PricingController : forwards to
    PricingController --> PricingService : delegates
    PricingController ..> PricingRequestDto : validates @Valid
    PricingController ..> PricingResponseDto : returns
    PricingController ..> GlobalExceptionHandler : intercepted on error

    PricingService --> PricingCalculator : math & rules
    PricingService --> PricingRepository : persists
    PricingService --> PricingAuditService : audit record
    PricingService --> PricingMetrics : emits metrics
    PricingService --> StructuredLogger : emits structured JSON
    PricingService ..> PricingRequest : creates entity

    PricingAuditService --> PricingAuditRepository : writes audit log
    PricingAuditService ..> PricingAuditLog : creates entity
    PricingRepository ..> PricingRequest : manages
    PricingAuditRepository ..> PricingAuditLog : manages
```

---

## 3. Low-Level Sequence Diagram — Single Request Lifecycle

This sequence diagram illustrates the step-by-step execution flow of a loan pricing request through the filter chain, custom OpenTelemetry child spans, calculation engine, database transactions, and telemetry recording.

```mermaid
sequenceDiagram
    autonumber
    actor Client as 🌐 Client (Browser / App)
    participant Filter as 🛡️ MDCFilter
    participant Ctrl as 🎮 PricingController
    participant Svc as ⚙️ PricingService
    participant Calc as 🧮 PricingCalculator
    participant Repo as 💾 PricingRepository
    participant Audit as 📜 PricingAuditService
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
        Svc->>Repo: save(PricingRequest)
        activate Repo
        Repo->>DB: INSERT INTO pricing_requests (...)
        DB-->>Repo: Saved Entity (id=101)
        Repo-->>Svc: Saved PricingRequest
        deactivate Repo
    end

    %% Audit & Metrics
    rect rgb(255, 245, 245)
        Note over Svc,Audit: Step D: Compliance Audit & Telemetry
        Svc->>Audit: recordSuccess(PricingRequest, traceId, ip)
        activate Audit
        Audit->>DB: INSERT INTO pricing_audit_logs (...)
        DB-->>Audit: OK
        Audit-->>Svc: Audit Recorded
        deactivate Audit

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

## 4. Low-Level Database Schema (ERD)

```mermaid
erDiagram
    PRICING_REQUESTS ||--o{ PRICING_AUDIT_LOGS : "tracks regulatory audit entries for"

    PRICING_REQUESTS {
        BIGINT id PK "Primary Key (Auto-increment)"
        VARCHAR(20) customer_id "Indexed, Masked in Logs"
        VARCHAR(50) product_type "HOME_LOAN, PERSONAL_LOAN, etc."
        DECIMAL(15_2) loan_amount "Requested Principal"
        INT loan_tenure_months "Tenure in months"
        INT credit_score "CIBIL / Experian score (300-900)"
        DECIMAL(15_2) annual_income "Used for FOIR calculation"
        DECIMAL(5_2) calculated_rate "Final computed interest %"
        DECIMAL(15_2) emi_amount "Monthly payment"
        DECIMAL(18_2) total_payable_amount "Principal + Total Interest"
        DECIMAL(18_2) total_interest_payable "Total interest paid"
        VARCHAR(20) risk_category "LOW, MEDIUM, HIGH"
        VARCHAR(20) status "APPROVED, REJECTED"
        BIGINT processing_time_ms "Calculation execution time"
        VARCHAR(45) request_ip "Client IP (Proxy Aware)"
        VARCHAR(64) trace_id "Indexed OTel Trace ID"
        TIMESTAMP created_at "Created timestamp"
        TIMESTAMP updated_at "Updated timestamp"
    }

    PRICING_AUDIT_LOGS {
        BIGINT id PK "Primary Key (Auto-increment)"
        BIGINT pricing_request_id FK "References pricing_requests.id"
        VARCHAR(20) customer_id "Customer ID"
        VARCHAR(50) action "CALCULATE_PRICING, REJECT_PRICING"
        VARCHAR(500) description "Audit justification details"
        VARCHAR(100) performed_by "SYSTEM / Operator"
        VARCHAR(20) outcome "SUCCESS / REJECTED"
        VARCHAR(45) request_ip "Client IP"
        VARCHAR(64) trace_id "OTel Trace ID"
        BIGINT duration_ms "Operation latency"
        TIMESTAMP created_at "Immutable audit timestamp"
    }
```

---

## Summary of Key Design Highlights for Interviews & Presentations

| Aspect | Implementation Details |
|---|---|
| **Architectural Style** | Domain-centric Layered Microservice with strict unidirectional downward dependency flow. |
| **Concurrency & Transactions** | `@Transactional` on calculation orchestration; HikariCP connection pooling tuned for YugabyteDB. |
| **Distributed Tracing** | 3-tier hierarchy (Controller Root Span $\rightarrow$ Service Child Span $\rightarrow$ Repository Operations). |
| **Observability Pillars** | Correlated via `traceId` across **Prometheus** (Metrics), **Loki** (Logs), and **Tempo** (Traces). |
| **Banking Compliance** | Synchronous immutable audit logging, PII masking in all log output, fail-fast database credentials, and Actuator lockdown. |
