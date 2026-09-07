# ePricing Service — Architecture Explained
## Every Design Decision Justified for Bank

---

## SYSTEM ARCHITECTURE OVERVIEW

```
┌──────────────────────────────────────────────────────────────────┐
│                    Client Layer                                   │
│  Postman │ React Frontend │ Mobile App │ Other Microservices     │
└───────────────────────────┬──────────────────────────────────────┘
                            │ HTTP/HTTPS on port 8080
                            ▼
┌──────────────────────────────────────────────────────────────────┐
│                   API Gateway (Production)                        │
│  Rate Limiting │ Authentication │ SSL Termination │ Load Balance  │
│  (Not in this project — Dockerized for local learning)           │
└───────────────────────────┬──────────────────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────────────────┐
│              ePricing Service (Spring Boot, Port 8080)           │
│                                                                   │
│  Filter Chain:                                                    │
│  MDCFilter(1) → SecurityFilter(2) → Spring DispatcherServlet     │
│                                           │                       │
│  Controllers:                             ↓                       │
│  /pricing → PricingController             │                       │
│  /health  → HealthController              │                       │
│  /metrics-demo → MetricsDemoController    │                       │
│                                           ↓                       │
│  Service Layer:                                                   │
│  PricingService ←→ PricingAuditService                           │
│       │                   │                                       │
│       ├── PricingCalculator (pure math)                           │
│       ├── PricingMetrics (Micrometer)                             │
│       ├── StructuredLogger (SLF4J)                                │
│       └── Tracer (OpenTelemetry)                                  │
│                                           │                       │
│  Repository Layer:                        ↓                       │
│  PricingRepository ←─── H2 In-Memory Database                    │
│  PricingAuditRepository                                           │
│                                                                   │
│  Management Port 8081:                                            │
│  /actuator/health │ /actuator/prometheus │ /actuator/metrics      │
└──────────┬────────┴──────────┬───────────┴──────────┬────────────┘
           │                   │                       │
    (metrics)              (logs)                  (traces)
           │                   │                       │
           ▼                   ▼                       ▼
    ┌──────────┐        ┌───────────┐          ┌────────────┐
    │Prometheus│        │  Promtail  │          │OTel Collect│
    │  :9090   │        │ reads file │          │  :4317/18  │
    └────┬─────┘        └─────┬─────┘          └─────┬──────┘
         │                    │                       │
         │              ┌─────▼─────┐                 │
         │              │   Loki    │                 │
         │              │  :3100    │                 │
         │              └─────┬─────┘                 │
         │                    │                       │
         └────────────────────┴───────────────────────┘
                              │
                              ▼
                      ┌──────────────┐
                      │   Grafana    │
                      │   :3000      │
                      │  Dashboards  │
                      │  Alerts      │
                      │  Explore     │
                      └──────────────┘
```

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

## PRODUCTION ADDITIONS (Not in this project — future work)

| Component | Purpose | Replaces |
|---|---|---|
| PostgreSQL / Oracle | Persistent database | H2 in-memory |
| Grafana Tempo | Distributed trace storage | OTel debug exporter |
| Alertmanager | Alert routing to PagerDuty/Slack | (missing currently) |
| Spring Security + JWT | API authentication | (absent for simplicity) |
| Resilience4j | Circuit breaker, retry, bulkhead | (absent for simplicity) |
| Spring Cloud Config | Centralized config management | Local application.yml |
| Kubernetes | Container orchestration | Docker Compose |
| Istio Service Mesh | mTLS, distributed tracing without code | OTel manual instrumentation |
| Grafana OnCall | On-call management, escalation | (absent) |
| Thanos / Cortex | Long-term Prometheus storage | Prometheus local storage |

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


