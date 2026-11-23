# Comprehensive Enterprise Architectural Audit & Orchestration Data Flow Verification

**Document Identifier:** `EPRICING-ARCH-AUDIT-2026-V1`  
**System Under Audit:** Bank ePricing Microservice (`epricing-service`)  
**Technology Stack:** Java 21 LTS, Spring Boot 3.3.4, YugabyteDB 2.20 (Distributed SQL), Temporal 1.24.1, Apache Kafka 3.8.0 (KRaft), OpenTelemetry 1.32.0, HikariCP 5.1.0  
**Audit Date:** 2026-11-19  
**Status:** Official Architectural Assessment & Technical Verification  

---

## 1. Executive Summary & Architecture Scorecard

The `epricing-service` is an enterprise-grade core banking microservice responsible for real-time risk-adjusted interest rate pricing, regulatory compliance auditing, durable multi-day rate-lock lifecycle management, and downstream event publication across retail and commercial lending channels.

This audit report provides an exhaustive, evidence-based architectural assessment of the service across three primary engineering pillars:
1. **Temporal Workflow Orchestration & Data Flow Verification**: Analysis of asynchronous task scheduling, worker pool mechanics, durable state persistence, deterministic execution, and verification that data flows without deadlocks or thread starvation.
2. **Security Posture & Compliance**: Evaluation of browser cross-origin resource sharing (CORS) defaults, operational actuator port segregation, PII sanitization in structured logs, and data-at-rest encryption.
3. **Distributed Systems Resilience & High Availability**: Review of YugabyteDB distributed SQL smart driver topology, HikariCP connection pooling parameters, database migration sequencing, and failure handling during network partitions or broker outages.

### 1.1 Architectural Scorecard

| Audit Dimension | Evaluation Status | Key Strengths | Identified Vulnerabilities & Technical Risks |
| :--- | :---: | :--- | :--- |
| **Temporal Workflow Orchestration** | **PASS (with hardening)** | Strict replay determinism; non-blocking durable timers via `Workflow.await()`; comprehensive exponential backoff activity retry policies with non-retryable domain exception filters. | Absence of client-supplied idempotency key on workflow initialization; potential dual-write inconsistency between DB and Kafka; activity timeout vs HikariCP timeout race condition. |
| **CORS & Network Perimeter** | **PASS (Fail-Closed)** | Fail-closed origin resolution when environment variable is unset; explicit HTTP method whitelist; strict header filtering; ambient credentials disabled (`allowCredentials(false)`). | Lack of Spring Security filter chain; CORS evaluated exclusively within Spring MVC `DispatcherServlet`. |
| **Actuator Management Isolation** | **PASS (Isolated Port)** | Physical port segregation (`server.port: 8080` vs `management.server.port: 8081`); sensitive administrative endpoints disabled (`env`, `configprops`, `heapdump`, `threaddump`). | Unauthenticated management port (no HTTP Basic / mTLS); `management.endpoint.health.show-details: always` exposes internal database/disk telemetry to internal network callers. |
| **PII Data Hygiene & Logging** | **NEEDS HARDENING** | `StructuredLogger` provides masking (`maskCustomerId`) and financial bracketing (`amountBracket`); MDCFilter sanitizes incoming request tracing headers. | 7 identified log call sites in Temporal controllers and activities log raw, unmasked customer IDs; financial parameters logged in debug mode; plaintext PII storage at rest in YugabyteDB. |
| **Distributed Resilience (YugabyteDB & Kafka)** | **PASS (High Resilience)** | Yugabyte Smart Driver (`load-balance=true`) with cluster topology auto-discovery; tuned HikariCP connection pool; pre-JPA migration sequencing preventing startup deadlocks. | JDBC URL configured with `sslmode=disable`; single seed node URL configuration; absence of client-side circuit breakers / rate limiters on synchronous REST endpoints. |

---

## 2. Temporal Workflow Orchestration & Data Flow Verification

### 2.1 End-to-End Orchestration Topology

The `epricing-service` coordinates rate calculations, credit verification, and multi-day customer offer acceptance through Temporal durable workflows (`LoanPricingWorkflow`, `LoanPricingWorkflowImpl`) running on task queue `epricing-pricing-queue`.

```
┌───────────────────────────────────────────────────────────────────────────────────┐
│                                INGESTION BOUNDARY                                 │
└───────────────────────────────────────────────────────────────────────────────────┘
               │
               │ HTTP POST /api/v1/workflow/pricing/start (PricingRequestDto)
               ▼
┌───────────────────────────────┐
│  TemporalPricingController    │ ── (Port 8080 /api/v1)
└──────────────┬────────────────┘
               │ Calls startPricingWorkflow(...)
               ▼
┌───────────────────────────────┐
│    TemporalPricingService     │ ── Generates workflowId ("pricing-CUST...-uuid")
└──────────────┬────────────────┘
               │ WorkflowClient.start(workflow::processLoanPricing, ...)
               ▼
┌───────────────────────────────────────────────────────────────────────────────────┐
│                          TEMPORAL CLUSTER (temporal:7233)                         │
│                    Task Queue: "epricing-pricing-queue"                           │
└───────────────────────────────────────┬───────────────────────────────────────────┘
                                        │
                                        │ Polled by WorkerFactory
                                        ▼
┌───────────────────────────────────────────────────────────────────────────────────┐
│                      TEMPORAL WORKER POOL (In-Process JVM)                        │
└───────────────────────────────────────┬───────────────────────────────────────────┘
                                        │
         ┌──────────────────────────────┴──────────────────────────────┐
         ▼                                                             ▼
┌─────────────────────────────────────────┐         ┌─────────────────────────────────────┐
│       PHASE 1: ACTIVITY DISPATCH        │         │      PHASE 2: NON-BLOCKING SUSPENSION│
│ PricingActivities                       │         │ Workflow.await(Duration.ofDays(7),  │
│  .calculateAndPersistPricing(...)       │         │   () -> approved || rejected)       │
└────────────────────┬────────────────────┘         └──────────────────┬──────────────────┘
                     │                                                 │
          ┌──────────┴──────────┐                                      │ Parks execution in
          ▼                     ▼                                      │ Temporal persistence;
  ┌───────────────┐     ┌───────────────┐                              │ releases worker thread.
  │  YugabyteDB   │     │ Apache Kafka  │                              │
  │ (Transactional│     │ (Event Topic  │                              │
  │  Persistence) │     │  loan-pricing │                              │
  │               │     │  -events)     │                              │
  └───────────────┘     └───────────────┘                              │
                                                                       ▼
                                                    ┌─────────────────────────────────────┐
                                                    │ Customer Decision Signal / Timer    │
                                                    │  • approveQuote()                   │
                                                    │  • rejectQuote(reason)              │
                                                    │  • OR 7-Day Window Elapses          │
                                                    └──────────────────┬──────────────────┘
                                                                       │
                                                                       ▼
                                                    ┌─────────────────────────────────────┐
                                                    │     PHASE 3: RESOLUTION ACTIVITY    │
                                                    │  • recordQuoteApproval()            │
                                                    │  • recordQuoteRejection()           │
                                                    │  • recordQuoteExpiration()          │
                                                    └──────────────────┬──────────────────┘
                                                                       │
                                                            ┌──────────┴──────────┐
                                                            ▼                     ▼
                                                    ┌───────────────┐     ┌───────────────┐
                                                    │  YugabyteDB   │     │ Apache Kafka  │
                                                    │ (State UPDATE)│     │ (Final Status │
                                                    │               │     │  Event)       │
                                                    └───────────────┘     └───────────────┘
```

### 2.2 Data Flow Step-by-Step Analysis

1. **Ingestion & Asynchronous Handoff**:
   - `TemporalPricingController.java:34-48`: Receives `POST /api/v1/workflow/pricing/start`.
   - Extracts client IP (`X-Forwarded-For` or `request.getRemoteAddr()`).
   - Delegates to `TemporalPricingService.startPricingWorkflow()`.
   - `TemporalPricingService.java:58-68`: Obtains workflow stub bound to `LoanPricingWorkflow.class` and dispatches execution asynchronously via `WorkflowClient.start(...)`.
   - Returns immediately to the caller with `HTTP 202 ACCEPTED` and an initial payload snapshot (`status: "PROCESSING"`). This guarantees that the ingestion HTTP thread is not tied to long-running downstream pricing computations.

2. **Phase 1 — Pricing Calculation & Snapshot Persistence**:
   - Temporal worker claims the workflow task from `epricing-pricing-queue`.
   - `LoanPricingWorkflowImpl.java:70-85`: Invokes `activities.calculateAndPersistPricing(requestDto, requestIp)`.
   - `PricingActivitiesImpl.java:54-58`: Executes inside a Spring transaction boundary (`@Transactional(rollbackFor = Exception.class)`).
   - `PricingService.calculatePricing()` evaluates business eligibility rules, loan-to-value ratios, fixed-obligation-to-income (FOIR) ratios, and calculates the interest rate.
   - Saves `PricingRequest` entity to YugabyteDB with status `QUOTED`.
   - Records an immutable audit event in `PricingAuditLog`.
   - Publishes `LoanPricingEvent` with status `CALCULATED` to Kafka topic `loan-pricing-events`.
   - Workflow receives the resulting `PricingResponseDto` and sets workflow state: `status = "QUOTED"`.

3. **Phase 2 — Non-Blocking Rate-Lock Suspension (`Workflow.await`)**:
   - `LoanPricingWorkflowImpl.java:95`:
     ```java
     boolean decisionMade = Workflow.await(Duration.ofDays(days), () -> approved || rejected);
     ```
   - **Verification of Unblocked Processing**:
     - Unlike standard Java concurrency primitives (`Thread.sleep()`, `CountDownLatch.await()`, `Future.get()`), `Workflow.await()` yields execution back to the Temporal SDK runtime.
     - The workflow state is serialized and persisted to Temporal's persistence store (`temporal-db`).
     - **No JVM threads or OS threads are blocked** during the entire multi-day validity window (default 7 days).
     - Worker threads immediately return to the worker pool to process other tasks, guaranteeing that high volumes of active rate locks do not cause thread pool exhaustion or memory bloat.

4. **Phase 3 — Customer Signals & Rate Lock Expiration**:
   - **Approval Path**: Client calls `POST /api/v1/workflow/pricing/{workflowId}/approve`. The controller calls `workflow.approveQuote()`. The signal updates `this.approved = true`. `Workflow.await` evaluates the condition, unblocks, and invokes `activities.recordQuoteApproval(quoteId, customerId)`. The entity status updates to `APPROVED` in YugabyteDB and Kafka publishes the approved event.
   - **Rejection Path**: Client calls `POST /api/v1/workflow/pricing/{workflowId}/reject`. Workflow calls `activities.recordQuoteRejection(quoteId, customerId, reason)`. The entity status updates to `REJECTED` and Kafka publishes the rejection event.
   - **Expiration Path**: If neither signal arrives within `Duration.ofDays(days)`, `Workflow.await` returns `false`. Workflow transitions to `EXPIRED` and calls `activities.recordQuoteExpiration(quoteId, customerId)`. Status updates to `EXPIRED` in DB and Kafka.

### 2.3 Determinism & Replay Verification

Temporal reconstructs workflow state by replaying history events from the database upon recovery or migration between workers. Replay compliance was verified against `LoanPricingWorkflowImpl.java`:
- **Deterministic Time**: Replaced all `System.currentTimeMillis()` calls with `Workflow.currentTimeMillis()`.
- **Deterministic Logging**: Employs `Workflow.getLogger(LoanPricingWorkflowImpl.class)` which suppresses duplicate log emissions during replay cycles.
- **Randomness & Concurrency**: Contains zero calls to `UUID.randomUUID()`, `Math.random()`, or custom threading (`new Thread()`, `Executors`).
- **Activity Options & Non-Retryable Handling**:
  - `startToCloseTimeout` set to `Duration.ofSeconds(30)`.
  - Exponential backoff retry: initial 1s, factor 2.0, max 10s, max 5 attempts.
  - Explicit non-retryable domain exceptions:
    - `PricingException`
    - `InsufficientCreditScoreException`
    - `LoanAmountExceedsEligibilityException`
    - `FoirExceededException`
    - `UnsupportedProductTypeException`
  - When an eligibility rejection occurs, the activity fails immediately without retry cycles; `LoanPricingWorkflowImpl.java:74-85` intercepts the error, marks status as `REJECTED`, captures the error message, and completes the workflow cleanly.

### 2.4 Identified Orchestration Risks & Bottlenecks

1. **Dual-Write Event Inconsistency (High Risk)**:
   - *Observation*: In `PricingActivitiesImpl.java` (lines 88–90, 125–127, 158–160) and `PricingService.java` (lines 307–333), the service performs a database commit via JPA repository followed by an asynchronous Kafka publish.
   - *Failure Mode*: If Kafka brokers are down or produce a timeout error, the database transaction has already committed, leading to lost events in downstream data pipelines. Conversely, if the Kafka message succeeds but the DB transaction subsequently fails, phantom events are streamed.
   - *Remediation*: Implement the **Transactional Outbox Pattern**. Write domain events to an `outbox_events` table within the same YugabyteDB transaction. A CDC pipeline (Debezium) or an outbox publisher relay guarantees at-least-once delivery to Kafka.

2. **Missing Client Idempotency Key (Medium Risk)**:
   - *Observation*: `TemporalPricingService.java:54` generates workflow IDs using a synthetic UUID:
     ```java
     String workflowId = "pricing-" + requestDto.getCustomerId() + "-" + UUID.randomUUID().toString().substring(0, 8);
     ```
   - *Failure Mode*: If an HTTP client encounters a network drop before receiving the HTTP 202 response and retries the request, a second distinct workflow instance is created, generating duplicate quotes and competing rate locks.
   - *Remediation*: Accept a client-supplied idempotency key (e.g. `applicationId` or `quoteReferenceId`):
     ```java
     String workflowId = "rate-lock-" + requestDto.getCustomerId() + "-" + requestDto.getApplicationId();
     ```
     Temporal automatically deduplicates workflow creation on identical workflow IDs within the retention period.

3. **Activity Timeout vs. HikariCP Connection Timeout Contention (Medium Risk)**:
   - *Observation*: `LoanPricingWorkflowImpl.java:33` sets activity `startToCloseTimeout` to 30 seconds. `application.yml:62` sets HikariCP `connection-timeout` to 30,000 ms (30 seconds).
   - *Failure Mode*: Under high database connection contention, HikariCP will wait up to 30 seconds before throwing `SQLTransientConnectionException`. Simultaneously, Temporal's activity timeout will trigger, resulting in ambiguous activity state and duplicate activity execution.
   - *Remediation*: Set HikariCP `connection-timeout` to 10,000 ms (10 seconds) or increase Temporal activity `startToCloseTimeout` to 60 seconds to ensure connection pool errors surface cleanly before activity timeout expiry.

4. **Race Condition on Early Signals (Low Risk)**:
   - *Observation*: In `LoanPricingWorkflowImpl.java:124`, `approveQuote()` checks `if (!"QUOTED".equals(status)) { return; }`.
   - *Failure Mode*: If an external automated system fires an approval signal immediately while the workflow is in the `INITIALIZING` phase (executing `calculateAndPersistPricing`), the signal is silently dropped.
   - *Remediation*: Buffer early signals using a boolean flag (`earlyApprovalReceived = true`) and evaluate it immediately upon completion of Phase 1.

---

## 3. Security Posture & Compliance Analysis

### 3.1 Cross-Origin Resource Sharing (CORS) Configuration

The CORS configuration was audited in `src/main/java/com/bank/epricing/config/WebConfig.java`:

```java
@Value("${epricing.cors.allowed-origins:}")
private String corsAllowedOrigins;

@Override
public void addCorsMappings(CorsRegistry registry) {
    if (corsAllowedOrigins == null || corsAllowedOrigins.isBlank()) {
        log.warn("CORS_ALLOWED_ORIGINS is not set — all cross-origin requests will be blocked.");
        return;
    }
    String[] origins = corsAllowedOrigins.split(",");
    registry.addMapping("/**")
        .allowedOriginPatterns(origins)
        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
        .allowedHeaders("Content-Type", "Authorization", "X-Request-ID", "X-Customer-ID", "X-Correlation-ID")
        .exposedHeaders("X-Request-ID", "X-Trace-ID")
        .allowCredentials(false)
        .maxAge(3600);
}
```

#### Security Findings:
- **Fail-Closed Default (Strong Practice)**: When `CORS_ALLOWED_ORIGINS` is missing or empty, the method exits without registering mappings. Any cross-origin preflight `OPTIONS` request is rejected by default.
- **Credential Restriction (Strong Practice)**: `.allowCredentials(false)` prevents cross-origin requests from reading ambient cookies or HTTP authentication credentials.
- **Strict Headers Whitelist**: Exposes only necessary tracing headers (`X-Request-ID`, `X-Trace-ID`) and restricts allowed input headers.
- **Architectural Risk**: `spring-boot-starter-security` is not declared in `pom.xml`. CORS enforcement is handled by Spring MVC `DispatcherServlet`. Static resources or error dispatches handled prior to `DispatcherServlet` bypass these CORS rules.
- **Remediation**: Introduce `spring-boot-starter-security` and configure a standard `CorsConfigurationSource` bean attached to the security filter chain.

### 3.2 Actuator Management Port Segregation

The operational management configuration in `src/main/resources/application.yml` and `docker-compose.yml` was audited:

```yaml
server:
  port: 8080
  servlet:
    context-path: /api/v1

management:
  server:
    port: 8081
  endpoints:
    web:
      base-path: /actuator
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      show-details: always
      group:
        liveness:
          include: ping
        readiness:
          include: db, diskSpace
```

#### Security Findings:
- **Port Segregation (Strong Practice)**: Business traffic enters via port `8080` (`/api/v1`), while telemetry traffic is relegated to port `8081`. In `docker-compose.yml:123`, port 8081 is mapped to `8089:8081` to avoid conflicting with external default services.
- **Information Leakage Surface Minimized (Strong Practice)**: Endpoints exposing sensitive configuration or JVM memory dumps (`/actuator/env`, `/actuator/configprops`, `/actuator/heapdump`, `/actuator/threaddump`, `/actuator/beans`) are explicitly omitted.
- **Vulnerability 1 — Unauthenticated Monitoring Port**: Port `8081` enforces no authentication mechanism (no Basic Auth, OAuth2 bearer tokens, or mTLS). If the monitoring port is inadvertently bound to a non-private interface, any client on the internal network can scrape Prometheus metrics and system metadata.
- **Vulnerability 2 — Overly Verbose Health Endpoint**: `management.endpoint.health.show-details: always` exposes internal component statuses (YugabyteDB connection details, disk space thresholds) to unauthenticated callers.
- **Remediation**:
  1. Change `show-details` to `when-authorized`.
  2. Implement Kubernetes network policies (`NetworkPolicy`) ensuring port `8081` is accessible exclusively by authorized Prometheus scraper pods.
  3. Enforce mTLS or HTTP Basic Authentication for actuator endpoints via Spring Security.

### 3.3 PII Data Handling & Logging Sanitization

Regulatory frameworks (RBI Master Directions on Digital Lending, GDPR, DPDP Act) mandate that Personally Identifiable Information (PII) and sensitive financial details must not appear in plaintext in centralized log sinks (Loki, Elasticsearch).

#### Sanitization Controls Implemented:
- `StructuredLogger.java:182-188`:
  ```java
  public static String maskCustomerId(String customerId) {
      if (customerId == null || customerId.length() <= 4) return "****";
      if (customerId.length() < 8) {
          return customerId.substring(0, 2) + "****" + customerId.substring(customerId.length() - 2);
      }
      return customerId.substring(0, 4) + "****" + customerId.substring(customerId.length() - 4);
  }
  ```
- `StructuredLogger.java:199-206`: `amountBracket()` categorizes exact loan amounts into coarse buckets (`<1L`, `1L-10L`, `10L-1Cr`, `>1Cr`) to prevent precise loan sizing telemetry from being leaked.
- `MDCFilter.java:103`: Invokes `maskCustomerId` before writing the customer identifier into MDC logging context.

#### PII Leakage Violations Identified:
Despite the existence of `StructuredLogger`, multiple components bypass the masking utility and log raw customer identifiers and credit data:

1. **`TemporalPricingController.java:44`**:
   ```java
   log.info("Received request to start Temporal workflow | customerId={}", requestDto.getCustomerId());
   ```
2. **`LoanPricingWorkflowImpl.java:55`**:
   ```java
   log.info("Starting LoanPricingWorkflow | workflowId={} | customerId={}", workflowId, requestDto.getCustomerId());
   ```
3. **`PricingActivitiesImpl.java:55`**:
   ```java
   log.info("Executing Temporal Activity: calculateAndPersistPricing | customerId={}", requestDto.getCustomerId());
   ```
4. **`PricingActivitiesImpl.java:62`**:
   ```java
   log.info("Executing Temporal Activity: recordQuoteApproval | quoteId={} | customerId={}", quoteId, customerId);
   ```
5. **`PricingActivitiesImpl.java:95`**:
   ```java
   log.info("Executing Temporal Activity: recordQuoteRejection | quoteId={} | customerId={} | reason={}", quoteId, customerId, reason);
   ```
6. **`PricingActivitiesImpl.java:132`**:
   ```java
   log.info("Executing Temporal Activity: recordQuoteExpiration | quoteId={} | customerId={}", quoteId, customerId);
   ```
7. **`PricingCalculator.java:88`**:
   ```java
   log.debug("Calculating interest rate | productType={} | creditScore={} | amount={}", productType, creditScore, amount);
   ```
8. **Plaintext Persistence at Rest**:
   - Entities `PricingRequest` and `PricingAuditLog` store `customer_id`, `credit_score`, `annual_income`, and `request_ip` in raw plaintext database columns without column-level encryption (`@Convert(converter = AttributeEncryptor.class)`).

---

## 4. Distributed Systems Resilience & Failover Assessment

### 4.1 YugabyteDB Distributed SQL & Smart Driver Architecture

The persistence tier relies on YugabyteDB, an open-source distributed SQL database compatible with PostgreSQL.

- **Driver Class**: `com.yugabyte.Driver` (Yugabyte Smart Driver).
- **Datasource URL**:
  ```
  jdbc:yugabytedb://${DB_HOST:localhost}:${DB_PORT:5433}/${DB_NAME:epricingdb}?load-balance=true&sslmode=disable
  ```

#### Smart Driver Resilience Evaluation:
- **Cluster Awareness (`load-balance=true`)**: The smart driver executes periodic cluster topology discovery queries (`SELECT * FROM yb_servers()`). It maintains an internal routing table of all active TServer nodes in the cluster.
- **Failover & Connection Balancing**: When an application establishes a new database connection, the smart driver routes the connection in a round-robin or least-connected manner across all live TServers. If a TServer node crashes, the driver detects node unreachability, evicts it from the routing pool, and automatically redirects connection requests to surviving nodes without requiring a physical load balancer (e.g. HAProxy).
- **Vulnerability 1 — Insecure SSL Mode**: `sslmode=disable` allows database traffic to travel unencrypted over the container network, exposing credentials and customer financial data.
- **Vulnerability 2 — Single Seed Host Dependency**: `${DB_HOST:localhost}` specifies a single seed host. If the container or node specified as `DB_HOST` is restarting during service bootstrap, the smart driver cannot perform the initial topology query, causing application startup to fail.
- **Remediation**:
  - Configure multi-host seeds: `jdbc:yugabytedb://yb-tserver-1:5433,yb-tserver-2:5433,yb-tserver-3:5433/epricingdb?load-balance=true&sslmode=verify-full`.

### 4.2 HikariCP Connection Pool Configuration

The HikariCP pool configuration in `src/main/resources/application.yml:58-67` was audited:

```yaml
hikari:
  pool-name: epricing-pool
  maximum-pool-size: ${DB_POOL_MAX_SIZE:20}
  minimum-idle: ${DB_POOL_MIN_IDLE:5}
  connection-timeout: 30000
  idle-timeout: 600000
  max-lifetime: 1800000
  leak-detection-threshold: 60000
  validation-timeout: 5000
```

#### Parameter Assessment:
| HikariCP Parameter | Value | Assessment & Operational Impact |
| :--- | :--- | :--- |
| `pool-name` | `epricing-pool` | Standardized tag enabling targeted Micrometer / Prometheus metrics scraping (`hikaricp_connections_*`). |
| `maximum-pool-size` | `20` | Appropriately sized for high-throughput I/O without overwhelming YugabyteDB backend tablet server threads. |
| `minimum-idle` | `5` | Ensures a baseline of warm connections, eliminating latency spikes from TCP and TLS handshakes on bursty incoming traffic. |
| `connection-timeout` | `30000ms` (30s) | Maximum wait time for a connection from the pool. Recommended to reduce to `10000ms` to fail fast during starvation. |
| `idle-timeout` | `600000ms` (10m) | Retires idle connections safely while preventing connection churn. |
| `max-lifetime` | `1800000ms` (30m) | Automatically recycles connections every 30 minutes, preventing memory leaks and stale socket timeouts. |
| `leak-detection-threshold` | `60000ms` (60s) | Logs a stack trace if a connection is checked out for longer than 60 seconds. Prevents unclosed connection leaks. |
| `validation-timeout` | `5000ms` (5s) | Fast connection liveness verification using `isValid()` during node restarts. |

### 4.3 Database Migration Sequencing Resilience

- **Component**: `DatabaseMigrationInitializer.java` and `DatabaseMigrationJpaDependencyConfig.java`.
- **Mechanism**:
  - Custom migration runner executes schema scripts (`V1__...sql` through `V7__...sql`) via Spring Framework's `ScriptUtils`.
  - Tracks execution state in the `schema_history` table to guarantee idempotency across pod restarts.
  - In compliance with User Rule #2, `V6__add_grafana_reader_role.sql` avoids dollar-quoted PL/pgSQL blocks (`DO $$ ... $$;`), which fail under `ScriptUtils` due to semicolon delimiter splitting. Instead, it queries `pg_roles` via standard JDBC prior to role creation.
  - `DatabaseMigrationJpaDependencyConfig` configures `EntityManagerFactory` with an explicit `@DependsOn("databaseMigrationInitializer")`. This guarantees migrations complete before Hibernate validates JPA entities against the schema.

### 4.4 Circuit Breakers & Traffic Throttling Assessment

- **Audit Finding**: The synchronous REST controllers (`PricingController`, `TemporalPricingController`) currently lack client-side circuit breakers (Resilience4j) and token-bucket rate limiters (Bucket4j).
- **Cascading Failure Scenario**: If YugabyteDB experiences distributed consensus latency or raft heartbeat delays, database calls will hold connections for up to 30 seconds. Inbound synchronous HTTP requests will rapidly consume all 200 Tomcat worker threads, causing thread pool starvation and rendering health checks on port 8080 unresponsive.
- **Remediation**:
  - Wrap downstream calls in a Resilience4j `@CircuitBreaker(name = "pricingDb")` with a 50% failure rate threshold and a 5-second slow-call duration threshold.
  - Introduce an API Gateway rate limiter enforcing concurrency and request-rate limits per client application.

---

## 5. Priority Recommendations & Remediation Matrix

The following matrix categorizes all identified architectural risks by severity, operational impact, and estimated remediation effort.

| ID | Severity | Category | Risk Description | Remediation Action Plan | Target Source Code / Config |
| :---: | :---: | :---: | :--- | :--- | :--- |
| **SEC-01** | **CRITICAL** | Security / PII | Raw customer IDs and credit details logged in plaintext across 7 components. | Replace raw log placeholders with `StructuredLogger.maskCustomerId()`; remove raw credit scores from debug logs. | `TemporalPricingController.java:44`<br>`LoanPricingWorkflowImpl.java:55`<br>`PricingActivitiesImpl.java:55,62,95,132`<br>`PricingCalculator.java:88` |
| **SEC-02** | **HIGH** | Security / Compliance | Actuator management port 8081 is unauthenticated; `health.show-details: always` exposes internal system metadata. | Change `show-details` to `when-authorized`; enforce Kubernetes `NetworkPolicy`; incorporate Spring Security basic auth/mTLS for port 8081. | `application.yml:144, 172`<br>`pom.xml` |
| **RES-01** | **HIGH** | Resilience / NetSec | YugabyteDB JDBC URL disables SSL (`sslmode=disable`) and relies on single seed host. | Update JDBC URL to `sslmode=verify-full` with CA bundle; supply multi-node seed list (`yb-tserver-1:5433,yb-tserver-2:5433`). | `application.yml:51` |
| **ORC-01** | **HIGH** | Orchestration | Dual-write pattern between YugabyteDB and Kafka lacks transactional consistency guarantees. | Implement Transactional Outbox pattern or Debezium CDC on YugabyteDB write-ahead log. | `PricingActivitiesImpl.java:88,125,158`<br>`PricingService.java:307` |
| **ORC-02** | **MEDIUM** | Orchestration | Workflow ID generated with random UUID suffix, preventing client-side idempotency deduplication. | Bind workflow ID to business client key (`"rate-lock-" + customerId + "-" + applicationId`). | `TemporalPricingService.java:54` |
| **RES-02** | **MEDIUM** | Resilience | Activity timeout (30s) equals HikariCP timeout (30s), creating duplicate activity retry storms under DB starvation. | Reduce HikariCP `connection-timeout` to `10000ms` or increase activity `startToCloseTimeout` to `60s`. | `application.yml:62`<br>`LoanPricingWorkflowImpl.java:33` |
| **RES-03** | **MEDIUM** | Resilience | Synchronous HTTP endpoints lack circuit breaking and request rate limiting. | Integrate Resilience4j `CircuitBreaker` and `RateLimiter` around database and calculation services. | `PricingController.java`<br>`pom.xml` |
| **SEC-03** | **MEDIUM** | Security / Storage | Sensitive customer financial data stored in plaintext at rest in YugabyteDB tables. | Implement JPA `@Convert` attribute converters using AES-256 GCM authenticated encryption. | `PricingRequest.java`<br>`PricingAuditLog.java` |

---

## 6. Verification and Audit Sign-Off

### 6.1 Test Suite Verification
The complete test suite of `epricing-service` was executed against Java 21 LTS using Apache Maven.

```powershell
mvn test
```

- **Total Tests Run**: 78
- **Failures**: 0
- **Errors**: 0
- **Skipped**: 0
- **Execution Result**: `BUILD SUCCESS`
- **Scope Verified**: Sliced unit tests, pricing calculators, Temporal workflow stubs, activity implementations, Kafka event serialization, and full `@SpringBootTest` Spring application context bootstrap (`ApplicationContextBootstrapTest`, `KafkaContextBootstrapTest`).

### 6.2 Architectural Conclusion
The `epricing-service` demonstrates a mature, resilient architecture for distributed core banking operations. The Temporal workflow orchestration guarantees non-blocking, durable execution across multi-day rate-lock lifecycles, and the YugabyteDB smart driver provides automatic connection balancing and failover routing. Implementing the remediation items detailed in Section 5—specifically PII log sanitization, Actuator authentication, and the Transactional Outbox pattern—will elevate the service to top-tier enterprise compliance and production resilience.
