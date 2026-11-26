# ePricing Service — Architecture Explained
## Every Design Decision Justified for Bank

---

## SYSTEM ARCHITECTURE OVERVIEW

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                      Client Layer                                      │
│   Postman │ React CRM Portal │ Mobile Loan App │ Interactive Showcase (showcase/)      │
└───────────────────────────────────────────┬────────────────────────────────────────────┘
                                            │ HTTP on :8080 (/api/v1/*)
                                            ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        ePricing Service (Spring Boot 3.3.4, Java 21)                   │
│                                                                                        │
│   Filter Chain:                                                                        │
│   MDCFilter(1) [Sanitizes PII] ──► OpenTelemetry MVC Filter ──► DispatcherServlet      │
│                                                                     │                  │
│   Controllers:                                                      │                  │
│   ├── /pricing ───────────► PricingController                       │                  │
│   ├── /workflow/pricing ──► TemporalPricingController               │                  │
│   └── /health ────────────► HealthController                        │                  │
│                                                                     ▼                  │
│   Service & Workflow Layer:                                                            │
│   ├── PricingService ◄──► PricingAuditService                                          │
│   ├── PricingCalculator (Reducing balance EMI formulas)                                │
│   ├── PricingActivitiesImpl (Temporal Activities & Event Trigger)                     │
│   ├── KafkaPricingEventProducer (Fail-fast 3000ms, W3C traceparent injection)          │
│   ├── PricingMetrics (Micrometer) & StructuredLogger (Sanitized PII)                   │
│   └── Tracer (OpenTelemetry 1.40.0)                                                    │
│                                                                                        │
│   Management Port 8089 (host) / 8081 (container):                                      │
│   /actuator/health │ /actuator/prometheus │ /actuator/metrics                          │
└───┬─────────────────────────┬──────────────────────┬──────────────────────┬────────────┘
    │                         │                      │                      │
    │ YSQL (:5433)            │ gRPC (:7233)         │ Kafka (:29092)       │ OTLP (:4318)
    ▼                         ▼                      ▼                      ▼
┌───────────────┐     ┌───────────────┐      ┌───────────────┐      ┌───────────────┐
│  YugabyteDB   │     │Temporal Server│      │ Apache Kafka  │      │ OTel Collector│
│Distributed SQL│     │   (:7233)     │      │ (KRaft :9092) │      │  (:4317/4318) │
│ epricingdb    │     │   Temporal DB │      │ Topic:        │      └───┬───────┬───┘
│   Tables:     │     │(Postgres :5432│      │ loan-pricing- │          │       │
│-pricing_reqs  │     │Temporal UI    │      │ events        │          │       │
│-audit_logs    │     │   (:8233)     │      │(3 Partitions) │          │       │
└───────┬───────┘     └───────────────┘      └───────┬───────┘          │       │
        │                                            │                  │       │
        │ CDC / Stream Ingestion                     │ ETL / Stream     │       │
        └─────────────────────┬──────────────────────┘                  │       │
                              ▼                                         │       │
                    ┌──────────────────┐                                │       │
                    │Analytics Lakehouse                                │       │
                    │ (BigQuery/Iceberg│                                │       │
                    │   Star Schema)   │                                │       │
                    └──────────────────┘                                │       │
                                                                        │       │
┌───────────────────────────────────────────────────────────────────────┼───────┼────────┐
│                        Full-Stack Observability Platform              │       │        │
│                                                                       ▼       │        │
│   ┌──────────────┐          ┌──────────────┐                   ┌──────────────┐        │
│   │  Prometheus  │◄─────────┤ Promtail     │                   │ Grafana Tempo│◄───────┘
│   │    :9090     │ Scrapes  │ reads files  │                   │    :3200     │
│   └───┬──────────┘          └──────┬───────┘                   └──────┬───────┘
│       │ Alerts                     │ Logs                             │ Traces
│       ▼                            ▼                                  │
│   ┌──────────────┐          ┌──────────────┐                          │
│   │ Alertmanager │          │ Grafana Loki │                          │
│   │    :9093     │          │    :3100     │                          │
│   └──────┬───────┘          └──────┬───────┘                          │
│          │ Webhook                 │                                  │
│          ▼                         │                                  │
│   ┌──────────────┐                 │                                  │
│   │Webhook Logger│                 │                                  │
│   │    :5001     │                 │                                  │
│   └──────────────┘                 │                                  │
│                                    ▼                                  ▼
│                             ┌──────────────────────────────────────────────┐
│                             │          Grafana Unified UI (:3000)          │
│                             │  Metrics + Logs + Traces + DB Dashboards     │
│                             └──────────────────────────────────────────────┘
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## END-TO-END DATA FLOW (THE 5-TIER DATA JOURNEY)

In the `epricing-service` enterprise banking architecture, data moves across four distinct operational, analytical, and telemetry pathways:

### 1. Synchronous Online Pricing & Underwriting Flow
1. **Client Ingress & PII Sanitization (`MDCFilter`, Order 1)**:
   - Client applications (Mobile CRM, Core Banking Portal, or Traffic Simulator) dispatch HTTP `POST /api/v1/pricing` with payload (`productType`, `loanAmount`, `creditScore`, `annualIncome`, `customerId`).
   - `MDCFilter` extracts or generates `X-Request-ID` and sanitizes `X-Customer-ID` via `StructuredLogger.maskCustomerId()` to `CUST****1234`.
   - Binds `{requestId, customerId="CUST****1234", clientIp, httpMethod="POST", requestUri="/api/v1/pricing"}` to SLF4J MDC.
   - OpenTelemetry Spring MVC filter auto-instruments the root trace (`traceId = 32-hex`, `spanId = 16-hex`) and bridges them into the logging context.
2. **Actuarial Calculation & Financial Rules (`PricingCalculator`)**:
   - `PricingController` verifies `@Valid` Bean constraints and delegates to `PricingService.calculatePricing()`.
   - Validates credit score bounds [300, 900] and minimum threshold (650).
   - Computes Fixed Obligation to Income Ratio (FOIR $\le$ 50%).
   - Calculates base benchmark interest rate + product risk margin (Home Loan: 1.0x, Auto: 1.1x, Personal: 1.5x) - credit tier discount - relationship volume discount ($\ge$ ₹50L).
   - Evaluates monthly Equated Installment (EMI) using exact reducing balance amortization formulas with `BigDecimal` (`HALF_EVEN` rounding).
3. **Atomic Persistence & HikariCP Pool Safety (`PricingAuditService`)**:
   - Atomic database persistence is deliberately confined to `PricingAuditService.savePricingWithAudit()` via `@Transactional(rollbackFor = Exception.class)` rather than outer `PricingService` methods, preventing HikariCP connection starvation deadlocks.
   - Atomically inserts `pricing_requests` with status `CALCULATED` and `pricing_audit_logs` with action `PRICING_CALCULATED` into **YugabyteDB** (YSQL port 5433).
4. **Post-Commit Asynchronous Domain Event Streaming (`KafkaPricingEventProducer`)**:
   - Post-commit hook (`TransactionSynchronizationManager.registerSynchronization`) triggers after the database transaction successfully commits.
   - Injects W3C `traceparent` (`00-{traceId}-{spanId}-01`) into Kafka `ProducerRecord` headers.
   - Publishes `LoanPricingEvent` to `loan-pricing-events` on Kafka broker (`kafka:29092`) keyed by `customerId` with `max.block.ms: 3000`. If Kafka is unreachable or partitioned, the fail-fast timeout prevents thread hanging and decouples HTTP 201 responses.
5. **Egress**:
   - `PricingController` returns HTTP 201 Created with JSON `PricingResponseDto` and `X-Request-ID` response header.
   - `MDCFilter` clears thread-local MDC in a `finally` block to prevent leakage across pooled Tomcat threads.

---

### 2. Durable Workflow Orchestration Flow (Temporal Engine)
1. **Workflow Initiation**:
   - Client sends `POST /api/v1/workflow/pricing/start`.
   - `TemporalPricingController` invokes `TemporalPricingService.startPricingWorkflow()`, returning HTTP 202 Accepted with a deterministic `workflowId` (e.g. `LP-REQ-1042`).
2. **Activity Task Execution**:
   - Temporal Server (`:7233`) dispatches task to `PricingActivitiesImpl.calculateAndPersistPricing()`.
   - Executes pricing calculation, commits to YugabyteDB, and emits `CALCULATED` event to Kafka.
3. **Durable State Suspension**:
   - Temporal enters `Workflow.await(7 days, ...)` rate-lock state timer.
   - JVM worker threads are completely released back to the operating system; workflow state and timers are durably maintained in Temporal's PostgreSQL database.
4. **Asynchronous Signal Resolution**:
   - **Customer Approval**: Client sends `POST /api/v1/workflow/pricing/{workflowId}/approve`. Temporal unblocks and executes `PricingActivitiesImpl.recordQuoteApproval()`, updating `pricing_requests` to `APPROVED`, writing audit log, and emitting `APPROVED` event to Kafka.
   - **Customer Rejection**: Client sends `POST /api/v1/workflow/pricing/{workflowId}/reject`. Updates status to `REJECTED` and publishes Kafka event.
   - **Expiration**: If 7 days elapse without customer action, timer fires; activity marks status `EXPIRED` and emits Kafka event.

---

### 3. Downstream Analytics Lakehouse Streaming Flow
1. **Bronze Ingestion (Raw Stream & CDC)**:
   - Streaming runners (Apache Beam / Google Cloud Dataflow or Spark Streaming) ingest JSON payloads from Kafka topic `loan-pricing-events` (partitioned by `customerId`) and Debezium change data capture feeds from YugabyteDB.
2. **Silver Ingestion (Data Hygiene & Privacy Masking)**:
   - Data pipeline coerces numeric bounds, normalizes IST timestamps to UTC ISO-8601, masks customer IDs (`CUST****1234`), and generates cryptographically salted HMAC-SHA256 pseudonym tokens (`customer_hash_token`) compliant with RBI and DPDP Act 2023.
3. **Gold Ingestion (Kimball Dimensional Star Schema in BigQuery / Iceberg)**:
   - Materializes dimensional entities: `dim_customer` (SCD Type 2), `dim_loan_product`, `dim_date`.
   - Materializes transactional and compliance facts: `fact_pricing_decisions` (grain: 1 row per pricing calculation) and `fact_audit_events` (grain: 1 row per lifecycle state transition).

---

### 4. Full-Stack Observability Telemetry Flow
1. **Metrics**: Micrometer instruments latency, throughput, and error counters $\to$ exposed at `/actuator/prometheus` (management port 8089) $\to$ scraped every 15s by Prometheus (`:9090`) $\to$ visualized in Grafana (`:3000`). Alerts evaluate and route via Alertmanager (`:9093`) to Webhook Logger (`:5001`).
2. **Logs**: Structured JSON written to `/app/logs/epricing-service.log` $\to$ tailed by Promtail $\to$ shipped to Loki (`:3100`) indexed by level, thread, and traceId.
3. **Traces**: OpenTelemetry SDK exports W3C spans via OTLP HTTP (`:4318`) $\to$ OpenTelemetry Collector $\to$ Grafana Tempo (`:3200`) $\to$ single-click trace-to-log correlation in Grafana.

---

## LAYER-BY-LAYER ARCHITECTURE DECISIONS

### 1. API Layer (Spring MVC + Tomcat)

**Decision: Spring Boot MVC over Spring WebFlux (Reactive)**
- Reactive is excellent for high I/O concurrency with few threads
- MVC with Tomcat thread-per-request is simpler to debug (thread dumps show request context)
- Our pricing calculations involve blocking JPA calls — reactive doesn't help here
- The team is familiar with MVC patterns
- Actuator, Spring Security, OpenTelemetry instrumentation have better MVC support

**Decision: Port separation (8080 API, 8081 Management)**
- API port: exposed to customers via load balancer, protected by WAF
- Management port: only accessible from ops subnet, VPN required
- If combined on one port: a misconfigured load balancer could expose `/actuator/heapdump` to internet

**Decision: Context path `/api/v1/`**
- `/api`: distinguishes from static assets (`/static/`), admin pages (`/admin/`)
- `/v1`: API versioning. When breaking changes needed: `/api/v2/pricing` while keeping `/api/v1/` for existing clients
- Controllers don't declare `/api/v1/` — this lives in `server.servlet.context-path`

### 2. Filter Layer

**Decision: MDCFilter as Order=1**
- Must run BEFORE everything else to ensure MDC is populated
- If MDCFilter runs after Spring Security, authentication logs won't have request context
- If MDCFilter runs after OTel, the OTel-generated traceId won't be in MDC for early log statements

**Decision: Thread-local MDC with mandatory clear()**
- Thread-local is the only thread-safe way to carry per-request context without passing it as a method parameter through every layer
- `MDC.clear()` in finally is non-negotiable: Tomcat thread pool reuses threads. Thread A's MDC must be clean before Thread A serves the next request

### 3. Business Logic Layer

**Decision: PricingCalculator as a stateless utility class**
- No database calls, no HTTP calls, no side effects
- Takes numbers in, returns numbers out
- This enables unit testing without any mocks: `new PricingCalculator().calculateInterestRate("HOME_LOAN", 750, new BigDecimal("5000000"))`
- Reusable across multiple service classes
- Can be extracted to a shared library JAR if multiple services need pricing calculation

**Decision: BigDecimal for all monetary values**
- IEEE 754 double-precision floating point cannot represent 0.1 exactly
- Banking arithmetic requires exact decimal arithmetic
- `BigDecimal.divide(divisor, 10, RoundingMode.HALF_UP)` matches RBI-specified rounding mode
- `scale=2` for final amounts (2 decimal places in INR), higher precision during intermediate calculations

**Decision: Separate exception hierarchy (PricingException)**
- `InsufficientCreditScoreException` carries enough information to log a meaningful audit entry
- `HttpStatus` attached to the exception eliminates if-else chains in GlobalExceptionHandler
- `errorCode` (machine-readable) allows clients to programmatically handle specific scenarios
- Inner classes keep all pricing exceptions in one file — contextually grouped

**Decision: @Transactional with REQUIRES_NEW for audit**
- Main transaction: Pricing calculation + pricing record save
- Audit transaction: Separate, survives main transaction rollback
- Why: If main transaction rolls back (DB error after calculation), the audit record of "what was attempted" is still valuable for forensics
- RBI audit requirement: All attempted transactions must be logged

### 4. Observability Layer

**Decision: Custom PricingMetrics class implementing MeterBinder**
- MeterBinder ensures metrics are registered after MeterRegistry is initialized (no circular dependency)
- All business metrics in one file — easy to audit "what business metrics exist?"
- Low cardinality tags only (`product_type=HOME_LOAN` with 5 possible values, not `customerId` with millions)

**Decision: 3-Tier OpenTelemetry Tracing (Controller -> Service -> Repository)**
- Auto-instrumentation captures the top-level HTTP request span at the Controller layer.
- Explicit child spans in `PricingService` capture business calculations (`calculatePricing`, `validateEligibility`, `computeInterestRate`).
- Explicit child spans capture Repository operations (`PricingRepository.save`, `PricingRepository.findByCustomerId`, `PricingRepository.findById`).
- This allows Grafana Tempo to visualize a complete 3-tier trace waterfall showing exact latency breakdown between API, Business Logic, and Database I/O.

**Decision: Complete ePricing Persistence Model**
- Stores both input parameters (`customerId`, `productType`, `loanAmount`, `loanTenureMonths`, `creditScore`, `annualIncome`) and calculation outputs (`calculatedRate`, `emiAmount`, `totalPayableAmount`, `totalInterestPayable`, `riskCategory`, `status`, `processingTimeMs`, `traceId`).
- Ensures complete historical reporting and regulatory compliance without loss of calculation details.

**Decision: StructuredLogger as a dedicated class**
- Enforces consistent log event structure across the codebase
- Single place to add new fields to all log events (add to StructuredLogger, reflects everywhere)
- Named events (`PRICING_STARTED`, `PRICING_COMPLETED`) enable Loki queries by event type
- Prevents developers from writing ad-hoc log statements with inconsistent field names

**Decision: GlobalExceptionHandler as observability hub**
- Every exception (business or technical) triggers: metric + log + span status change
- Consistent error response format (ErrorResponse DTO) for all clients
- traceId included in error response → support teams can find the trace immediately
- Generic Exception handler never exposes internal details → security best practice

### 5. Persistence Layer

**Decision: H2 in-memory database for local development**
- Zero setup — no PostgreSQL/Oracle installation required
- H2 console at `/h2-console` for visual inspection during development
- For production: same JPA code works with Oracle, MySQL, PostgreSQL — only JDBC URL changes
- `ddl-auto: create-drop` in dev → schema auto-created from entities. `ddl-auto: validate` in production → schema must already exist (managed by Flyway migrations)

**Decision: JPA indexes on customerId and traceId**
- `customerId`: Most common query pattern — "show me customer CUST001234's pricing history"
- `traceId`: Debug query pattern — "find the pricing record for trace abc123"
- Without indexes: Full table scan on every query. At 10M records: query takes minutes. With indexes: milliseconds.

**Decision: Immutable PricingAuditLog entity**
- Audit logs must never be modified (regulatory requirement)
- `@Column(updatable=false)` on all fields
- No `update()` or `delete()` methods in PricingAuditRepository
- In production: database-level permissions: `GRANT INSERT, SELECT ON pricing_audit_logs TO epricing_user` (no UPDATE, no DELETE)

### 6. Infrastructure Layer (Docker Compose)

**Decision: OTel Collector as telemetry router**
- Without collector: App has 3 exporters (Jaeger, Prometheus push, Loki)
- With collector: App has 1 OTLP exporter. Collector routes to all backends
- Vendor lock-in eliminated: Switching from Jaeger to Zipkin = change 3 lines in collector config
- Centralized processing: Sampling, PII redaction, batching in one place

**Decision: Promtail as log agent (not app-side log shipping)**
- App writes to file (simple, reliable, no network dependency)
- Promtail reads file and ships (separation of concerns)
- If Loki is down: logs accumulate in the file, Promtail catches up when Loki recovers
- If app directly shipped to Loki: Loki downtime blocks log writes → risk of losing logs

**Decision: Named Docker volumes for Prometheus, Grafana, Loki**
- Metric history and dashboards persist across `docker-compose down`/`up` cycles
- Without volumes: Every restart = Prometheus loses all metric history = empty dashboards
- `docker-compose down -v` explicitly deletes volumes when you want a clean slate

**Decision: Prometheus pull model over push**
- Prometheus controls scrape timing → consistent 15s intervals
- Prometheus knows immediately if a target is down (scrape fails)
- No firewall rules needed from app to Prometheus (Prometheus initiates connections)
- In auto-scaling: Prometheus discovers new instances via service discovery

---

## ACTIVE PLATFORM ARCHITECTURE & ENTERPRISE ROADMAP

### 1. Implemented Enterprise Platform Components (Active in Repository)

The `epricing-service` repository has evolved beyond a basic POC into a production-grade banking microservice ecosystem:

| Component | Status | Purpose & Implementation Details | Reference |
|---|:---:|---|---|
| **YugabyteDB (Distributed SQL)** | ✅ Active | Distributed ACID transactional storage wire-compatible with PostgreSQL (Port 5433). Runs database migrations V1..V8. | [`DatabaseDesign.md`](./database/DatabaseDesign.md) |
| **Apache Kafka 3.8.0 (KRaft)** | ✅ Active | Event streaming broker operating without ZooKeeper on ports 9092 and 29092. Emits `LoanPricingEvent` on 3 partitions. | [`KafkaKRaftBroker.md`](./messaging/KafkaKRaftBroker.md) |
| **Temporal Workflow Engine** | ✅ Active | Durable orchestration engine managing 7-day loan quote rate locks and asynchronous approval/rejection signals. | [`TemporalWorkflows.md`](./workflows/TemporalWorkflows.md) |
| **Grafana Tempo (Traces)** | ✅ Active | High-volume distributed trace storage querying on port 3200; visualizes multi-span waterfalls with OTel Collector. | [`OpenTelemetry.md`](./monitoring/OpenTelemetry.md) |
| **Prometheus Alertmanager** | ✅ Active | Alert deduplication, silencing, and notification routing on port 9093 with local webhook logging. | [`AlertingRules.md`](./monitoring/AlertingRules.md) |
| **Kubernetes Manifests** | ✅ Active | Full K8s deployment manifests, ConfigMaps, RBAC, and pod security standards in `k8s/`. | [`KubernetesDeployment.md`](./kubernetes/KubernetesDeployment.md) |
| **Interactive Showcase UI** | ✅ Active | Browser-based interactive showcase demonstrating real-time calculations, rate-lock states, and glassmorphism styling in `showcase/`. | [`InteractiveShowcase.md`](./showcase/InteractiveShowcase.md) |
| **Analytical Star Schema** | ✅ Active | High-fidelity dimensional Lakehouse mapping specification for downstream BigQuery/Iceberg analytics. | [`mapping-manifesto.md`](./mapping-manifesto.md) |
| **Architectural Audit** | ✅ Active | Formal architectural verification cataloging 5 dimensions and 7 hardening remediations. | [`architectural-audit.md`](./architectural-audit.md) |

### 2. Planned Future Roadmap Additions

| Component | Target Purpose | Architectural Motivation |
|---|---|---|
| **Spring Security + OAuth2 / JWT** | Zero-trust API authentication | Currently endpoints are unauthenticated for POC development; requires enterprise IDP integration (Keycloak/Okta). |
| **Transactional Outbox + Debezium CDC** | Zero-loss event streaming | Eliminates the dual-write window by committing events to an outbox table within the DB transaction before streaming to Kafka. |
| **Istio Service Mesh** | Dynamic mTLS & traffic shifting | Provides automated mTLS between microservice pods and canary routing without application-level TLS configuration. |
| **Thanos / Cortex** | Multi-year TSDB metrics archival | Offloads long-term metrics storage from Prometheus local disks to object storage (GCS/S3). |

---

## Bank PRODUCTION CONSIDERATIONS

### RBI Compliance
1. **Audit Trail:** Every pricing transaction has an immutable audit log (PricingAuditLog)
2. **Data Retention:** Audit logs must be retained for 7 years (configure Loki `retention_period: 61320h`)
3. **Data Localization:** All data (metrics, logs, traces) stored in Indian data centers
4. **PII Protection:** No sensitive customer data in logs — log customerId (not PAN, not account number)

### Security
1. **Non-root containers:** All Docker containers run as `epricing` user
2. **Secrets management:** No passwords in docker-compose.yml or application.yml in production — use HashiCorp Vault
3. **Network isolation:** Actuator port 8081 firewalled from internet — only accessible from ops subnet
4. **TLS everywhere:** All inter-service communication encrypted in production

### Scalability
1. **Stateless design:** No session state in Spring Boot → horizontal scaling
2. **HikariCP sizing:** `maximum-pool-size` tuned per instance count: if 10 instances × 10 connections = 100 DB connections max
3. **Async audit logging:** Audit doesn't block pricing response → consistent latency under load
4. **Metric cardinality:** All labels have bounded unique values → Prometheus stays within memory budget

### Disaster Recovery
1. **Prometheus retention:** 30 days local → Thanos for 1 year in object storage
2. **Log retention:** Loki 7 days local → S3 for long-term archival
3. **Database backup:** H2 replaced with Oracle (Bank's standard) with daily RMAN backups
4. **Multi-region:** Active-Passive setup — if Mumbai AZ fails, Hyderabad AZ takes over

---

## DETAILED OBSERVABILITY & TELEMETRY GUIDE (Manager Assignment Concepts)

### 1. JVM Memory Architecture: Heap vs Non-Heap

```
┌──────────────────────────────────────────────────────────────────────────┐
│                             JVM MEMORY                                   │
├──────────────────────────────────────────┬───────────────────────────────┤
│               HEAP MEMORY                │       NON-HEAP MEMORY         │
│  (Managed by Garbage Collector)          │  (Native OS Allocation)       │
│                                          │                               │
│  ┌──────────────────┬─────────────────┐  │  ┌─────────────────────────┐  │
│  │   Young Gen      │    Old Gen      │  │  │ Metaspace (Loaded       │  │
│  │   (Eden + S0/S1) │   (Tenured)     │  │  │ Class Bytecode)         │  │
│  └──────────────────┴─────────────────┘  │  ├─────────────────────────┤  │
│                                          │  │ Thread Stacks           │  │
│  • New Objects created here              │  │ Code Cache              │  │
│  • Subject to Frequent Minor GC          │  │ Direct Byte Buffers     │  │
│  • Long-lived objects promoted to Old    │  └─────────────────────────┘  │
└──────────────────────────────────────────┴───────────────────────────────┘
```

- **Heap Memory**: This is the dynamic memory space where Java instantiates all objects (e.g. `PricingRequestDto`, `PricingRequest`, `BigDecimal` calculations). The JVM Garbage Collector (GC) automatically scans Heap memory to destroy objects that are no longer referenced.
  - **Threshold Justification**: Above 85% Heap usage, the JVM struggles to find free space and triggers frequent "Full GC" cycles. At 100%, the application crashes with an `java.lang.OutOfMemoryError: Java heap space`.
- **Non-Heap Memory**: Stores JVM internal data structures such as **Metaspace** (compiled class bytecode, method metadata), **Thread Stacks** (memory allocated per thread), and **Code Cache** (JIT-compiled machine code). It is allocated directly from OS native RAM.

---

### 2. Garbage Collection (GC) & GC Pauses

- **Garbage Collection (GC)**: An automated background process in Java that frees up RAM by deleting unreferenced objects from the Heap.
- **Stop-The-World (STW) GC Pause**: When the Garbage Collector runs a Major/Full GC cycle, it freezes all application execution threads until memory compaction finishes. During an STW pause, incoming HTTP requests freeze and wait.
- **Monitoring GC in Grafana**:
  - `jvm_gc_pause_seconds_count`: Number of GC pauses per second.
  - `jvm_gc_pause_seconds_sum`: Total duration of time application threads spent frozen due to GC.

---

### 3. CPU Utilisation: Process CPU vs System CPU

- **Process CPU (`process_cpu_usage`)**: Measures the exact percentage of total host CPU cores currently being consumed strictly by the Java `epricing-service` process (value ranges from `0.0` to `1.0`).
- **System CPU (`system_cpu_usage`)**: Measures total CPU utilisation across all processes running on the host server/container host.
- **Threshold Justification**: When Process CPU exceeds **80% (0.80)**, CPU starvation occurs. Request execution threads compete for CPU cycles, causing latency spikes and thread queue backlogs.

---

### 4. Response Latency Percentiles: P50, P95, and P99

Why simple averages (means) are misleading in banking:
- Suppose 9 requests take **10ms** each, and 1 request takes **10,000ms (10s)**.
- **Average Latency**: `(9×10 + 10000)/10 = 1009ms (~1.0s)`. The average makes the system look acceptable, hiding the fact that 10% of customers experienced a 10-second freeze!

Percentiles provide accurate SLA visibility:
- **P50 (Median)**: 50% of requests are faster than this speed. Represents typical user experience.
- **P95 (95th Percentile)**: 95% of requests are faster than this speed. Represents the response SLA threshold required by banking compliance (RBI SLA < 2.0s).
- **P99 (99th Percentile)**: 99% of requests are faster than this speed. Uncovers tail-latency spikes under heavy load.

---

### 5. HikariCP Database Connection Pool Mechanics

- **Active Connections (`hikaricp_connections_active`)**: Database connections currently executing SQL queries.
- **Idle Connections (`hikaricp_connections_idle`)**: Pre-allocated database connections ready and waiting for new requests.
- **Pending Connections (`hikaricp_connections_pending`)**: Incoming HTTP threads waiting in queue because all connections in the pool are currently busy.
- **Bottleneck Detection**: If `pending > 0` for over 2 minutes, database connection pool exhaustion has occurred. Incoming pricing requests get blocked, cascading into HTTP 500 server timeouts.

---

### 6. Centralized Logging: Logback + Promtail + Loki

- **Structured JSON Logging (Logback)**: Output logs formatted in JSON containing standard key-value pairs (`timestamp`, `level`, `traceId`, `customerId`, `event_type`, `message`).
- **Promtail**: A lightweight agent that tails log files on disk, extracts structured fields, and ships them to Loki.
- **Loki**: A log aggregation database designed like Prometheus. It indexes log *labels* (e.g. `application="epricing-service"`, `level="ERROR"`) rather than indexing full text, providing 10x lower storage costs than Elasticsearch.
- **Root-Cause Analysis in Grafana**: You can query Loki logs directly inside Grafana using LogQL:
  ```logql
  {application="epricing-service"} | json | level="ERROR"
  ```

---

### 7. OpenTelemetry (OTel) & Distributed Tracing

- **Distributed Tracing**: Tracks the path of a single customer request as it flows across microservices, filters, databases, and external APIs.
- **Trace ID (`traceId`)**: A unique 128-bit hex string assigned to an HTTP request when it enters the system (e.g., `17baac0bb718f7993434fd1db718b4c7`).
- **Span ID (`spanId`)**: Identifies a specific unit of work within a trace (e.g. controller method, database query execution).
- **Correlation**: The `traceId` is automatically injected into SLF4J MDC, Logback JSON logs, HTTP response headers, and Grafana Tempo, creating 360-degree observability.

---

### 8. Container -> Nodes -> Pods Utilization Architecture

Modern cloud-native and Kubernetes banking infrastructure relies on a 3-tier resource utilization hierarchy:

```
┌─────────────────────────────────────────────────────────────────────────┐
  NODE LAYER (Virtual Machine / Physical Bare-Metal Server)
  Exporter: Node Exporter (:9100)
  Metrics: node_cpu_seconds_total, node_memory_MemTotal_bytes, node_filesystem
└────────────────────────────────────┬────────────────────────────────────┘
                                     │ Runs multiple pods/containers
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
  POD LAYER (Kubernetes Application Deployment Unit)
  Exporter: cAdvisor (:8080) / Kube-State-Metrics
  Metrics: Pod Count, Pod Status, Pod CPU Limits vs Usage, Pod Restarts
└────────────────────────────────────┬────────────────────────────────────┘
                                     │ Contains 1 or more containers
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
  CONTAINER LAYER (Docker Runtime Process: epricing, prometheus, grafana)
  Exporter: cAdvisor (:8080)
  Metrics: container_cpu_usage_seconds_total, container_memory_working_set_bytes
└─────────────────────────────────────────────────────────────────────────┘
```

#### Why Managers & Engineers Monitor This Hierarchy:
1. **Node Utilization**: Ensures the physical or cloud host server is not running out of CPU/Memory (CPU > 85%, RAM > 90%), which would cause host crash or node evictions.
2. **Pod Utilization**: Verifies that application deployment units are healthy, within quota limits, and not undergoing crash-loop restarts (`sum(changes(container_start_time_seconds[1h]))`).
3. **Container Utilization**: Pinpoints individual container memory leaks (`container_memory_working_set_bytes`), CPU throttling (`container_cpu_cfs_throttled_periods_total`), and network I/O saturation.

#### Grafana Dashboard Integration:
- **Dedicated Dashboard**: `container-nodes-pods-utilization.json` in Grafana provides full 3-tier drill-down visualization.
- **PromQL Utilization Queries**:
  - **Node CPU Utilization %**: `100 - (avg(rate(node_cpu_seconds_total{mode="idle"}[5m])) * 100)`
  - **Node RAM Utilization %**: `(1 - (node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)) * 100`
  - **Per-Container CPU %**: `sum(rate(container_cpu_usage_seconds_total[5m])) by (name) * 100`
  - **Container RAM Usage**: `sum(container_memory_working_set_bytes) by (name)`

---

### 9. Temporal Durable Workflow Orchestration

- **Engine**: Runs as a standalone service in `docker-compose.yml` (`temporalio/auto-setup:1.24.1` backed by PostgreSQL) exposing gRPC (`:7233`) and Web UI (`:8233`).
- **Workflow**: `LoanPricingWorkflow` manages the end-to-end pricing lifecycle (quote calculation, 7-day rate lock duration, approval/rejection signals, and automatic expiration) without consuming local JVM worker threads.
- **Activities**: `PricingActivities` invokes `PricingService` for calculations and `PricingAuditRepository` for immutable regulatory audit records.
- **REST APIs**: `TemporalPricingController` provides endpoints at `/api/v1/workflow/pricing/*` to initiate workflows, inspect snapshots, and dispatch asynchronous signals.
- **Lean Architecture**: Replaced the custom in-JVM Spring `@Async` thread pool (`ThreadPoolTaskExecutor`), eliminating JVM thread exhaustion and unrecovered task loss on server crashes.

---

### 10. Apache Kafka 3.8.0 KRaft Messaging Architecture

- **Engine**: Runs as a single-node cluster in ZooKeeper-less KRaft mode (`apache/kafka:3.8.0`) on ports `9092` (host) and `29092` (internal Docker network).
- **Domain Event Model**: Publishes strongly-typed `LoanPricingEvent` JSON records to the `loan-pricing-events` topic across 3 partitions.
- **Fail-Fast Producer Resilience**: Configured with `max.block.ms: 3000` to prevent Tomcat HTTP worker thread starvation during broker downtime.
- **Idempotency Guarantees**: Enforces `enable.idempotence: true`, `acks: all`, and `retries: 3` for zero-data-corruption message delivery to the broker.
- **Distributed Trace Propagation**: Injects standard W3C `traceparent` headers into Kafka records, maintaining distributed trace context into downstream analytical consumers.
- **Decoupled Error Handling**: DB persistence and HTTP 201 responses succeed even if Kafka encounters an outage, safeguarding core customer loan origination availability.
- **Downstream Analytics**: Streams directly into analytical star-schema pipelines ([`docs/mapping-manifesto.md`](./mapping-manifesto.md)) for BigQuery and Apache Iceberg Lakehouse reporting.

*For complete messaging specifications, see [`docs/messaging/KafkaKRaftBroker.md`](./messaging/KafkaKRaftBroker.md), [`docs/messaging/LoanPricingEventStreaming.md`](./messaging/LoanPricingEventStreaming.md), and [`docs/messaging/ProducerFailFast.md`](./messaging/ProducerFailFast.md).*


