# Bank ePricing Observability — COMPLETE LEARNING GUIDE
### Senior Principal Architect Teaching Series

---

> **Reading this guide**: Each section covers one file. Sections follow the same 10-part structure. Read top to bottom — each file builds on the previous.

---

# ═══════════════════════════════════════════════════════════════════
# FILE 1: pom.xml — Maven Project Object Model
# ═══════════════════════════════════════════════════════════════════

## 1. File Name and Purpose
`pom.xml` is the Maven build descriptor. It is the single file that defines what your project is, what it needs, and how to build it.

## 2. Why This File Exists
Before build tools existed, Java developers manually downloaded JARs, configured classpaths, and wrote shell scripts for compilation. In a project with 100+ transitive dependencies, this was impossible to maintain. `pom.xml` automates dependency resolution, compilation, testing, and packaging.

## 3. Underlying Theory

**Maven's Three Jobs:**
1. **Dependency Management** — Downloads JARs from Maven Central or Nexus, resolves transitive dependencies, handles version conflicts
2. **Build Lifecycle** — `validate → compile → test → package → install → deploy`
3. **Standard Structure** — Enforces `src/main/java`, `src/test/java`, `target/`

**Maven Coordinates:**
```
groupId    = com.bank       (who made it)
artifactId = epricing-service    (what it is)
version    = 1.0.0-SNAPSHOT      (which release)
```

**Dependency Scopes:**
| Scope | Compile | In JAR | Example |
|---|---|---|---|
| `compile` | YES | YES | spring-boot-starter-web |
| `runtime` | NO | YES | h2 (JDBC driver) |
| `test` | NO | NO | JUnit, Mockito |
| `optional` | YES | NO | Lombok |

**BOM (Bill of Materials):** A special POM declaring only versions. Ensures 20+ OTel libraries use compatible versions without manual version management.

**Fat JAR:** `spring-boot-maven-plugin:repackage` creates a single self-contained JAR with all dependencies embedded inside — runnable with `java -jar app.jar`.

## 4. How Spring Boot Processes It
Spring Boot's parent POM manages 300+ dependency versions. When you declare `spring-boot-starter-actuator` without a version, Maven looks up the parent's dependency management to find the compatible version.

## 5. Interactions With Other Files
- Every Java file: Cannot compile without dependency JARs declared here
- Dockerfile: `COPY target/*.jar /app/app.jar` — copies the JAR built by Maven
- logback-spring.xml: `logstash-logback-encoder` declared here enables JSON logging
- /actuator/prometheus: Only works if `micrometer-registry-prometheus` is declared here

## 6. Runtime Lifecycle
```
mvn clean package
    Maven downloads dependencies to ~/.m2/repository/
    javac compiles src/main/java/ → target/classes/
    spring-boot-maven-plugin:build-info → META-INF/build-info.properties
    spring-boot-maven-plugin:repackage → target/epricing-service.jar (80MB fat JAR)
```

## 7. Key Code Explanations

```xml
<!-- spring-boot-starter-actuator: Enables /actuator/health, /actuator/prometheus -->
<!-- Without this: Prometheus has nothing to scrape. Silent failure. -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>

<!-- micrometer-registry-prometheus: Translates Micrometer metrics to Prometheus text format -->
<!-- Without this: /actuator/prometheus returns 404 -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>

<!-- logstash-logback-encoder: Converts log events to JSON for Loki parsing -->
<!-- Explicit version required: NOT managed by Spring Boot parent BOM -->
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
</dependency>
```

## 8. Common Mistakes
| Mistake | Consequence |
|---|---|
| Missing micrometer-registry-prometheus | /actuator/prometheus returns 404 — silent failure |
| version=LATEST | Non-reproducible builds |
| Missing OTel BOM | Version conflicts between OTel libraries |
| Lombok not in annotation processor | @Data, @Slf4j not generating code |

## 9. Debugging Tips
```bash
# View full dependency tree
mvn dependency:tree

# Check specific library version
mvn dependency:tree -Dincludes=io.micrometer

# Verify fat JAR created correctly
jar tf target/epricing-service-1.0.0-SNAPSHOT.jar | grep "BOOT-INF/lib" | wc -l
```

## 10. Production Best Practices
- Use corporate parent POM extending spring-boot-starter-parent
- All JARs must pass OWASP CVE scan before deployment
- Use Nexus/Artifactory — never pull directly from Maven Central in production
- Pin exact versions in production — no SNAPSHOT, no LATEST

## 11. Interview Questions
1. What is spring-boot-starter-parent and why inherit from it?
2. What is the difference between a BOM and a regular dependency?
3. Why does h2 use scope=runtime but spring-boot-starter-web uses compile scope?
4. What does spring-boot-maven-plugin:repackage do?
5. What happens if you remove micrometer-registry-prometheus from pom.xml?

## 12. Hands-On Exercises
1. Remove micrometer-registry-prometheus. Run app. Hit /actuator/prometheus. Observe silent failure.
2. Run `mvn dependency:tree | grep micrometer`. Count transitive Micrometer JARs.
3. Run `mvn clean package` then examine `META-INF/build-info.properties` inside the JAR.

---

# ═══════════════════════════════════════════════════════════════════
# FILE 2: application.yml — Central Configuration
# ═══════════════════════════════════════════════════════════════════

## 1. File Name and Purpose
`application.yml` is the central configuration file for Spring Boot. It drives every aspect of application behavior without code changes.

## 2. Why This File Exists
Hardcoding configuration (ports, database URLs, pool sizes) in Java code means you cannot change behavior without recompiling. `application.yml` externalizes all configuration. The same JAR runs on your laptop with H2 and in production with Oracle.

## 3. Underlying Theory

**Spring Configuration Loading Order (last wins):**
1. Default values in Spring Boot
2. application.yml (in JAR)
3. application-{profile}.yml
4. Environment variables
5. System properties (-Dkey=value)
6. Command-line arguments (--server.port=9090)

**YAML vs Properties:** YAML supports hierarchy which maps naturally to Spring's nested property structure.

**Spring Profiles:** Different configs for different environments. `application-docker.yml` activates when `SPRING_PROFILES_ACTIVE=docker`.

## 4. How Spring Boot Processes It
1. Reads application.yml at startup
2. Merges with environment variables (env vars win)
3. Binds properties to @ConfigurationProperties beans
4. Actuator reads management.endpoints.web.exposure.include
5. HikariCP pool created with settings from spring.datasource.hikari.*

## 5. Key Configuration Explained

```yaml
management:
  server:
    port: 8081  # SECURITY: Actuator on separate port — never expose to internet
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true  # CRITICAL: enables p95/p99 queries in Grafana
```

**WHY separate management port (8081):** Port 8080 = customer-facing API. Port 8081 = internal monitoring. /actuator/heapdump downloads your entire JVM heap — catastrophic if publicly accessible.

## 6. Interview Questions
1. What is the order of priority for Spring Boot configuration sources?
2. Why should Actuator run on a separate management port in production?
3. What does percentiles-histogram: true enable?
4. What is a Spring Profile?
5. Why do we need tags: application: ${spring.application.name} in metrics config?

---

# ═══════════════════════════════════════════════════════════════════
# FILE 3: logback-spring.xml — Structured JSON Logging
# ═══════════════════════════════════════════════════════════════════

## 1. File Name and Purpose
Configures Logback to output structured JSON logs that Promtail can parse and ship to Loki.

## 2. Why This File Exists
Default plain-text logs cannot be efficiently queried in Loki. JSON logs allow:
- Label extraction (level, traceId, customerId)
- Field-based filtering in Grafana
- Correlation with metrics and traces via traceId

## 3. Underlying Theory

**SLF4J → Logback Pipeline:**
```
Your Code: log.info("message")
    SLF4J API (facade)
    Logback (implementation)
    Appender (Console / Rolling File)
    LogstashEncoder (converts to JSON)
    Output
```

**MDC (Mapped Diagnostic Context):** Thread-local key-value store. Micrometer Tracing automatically puts traceId and spanId here. Every JSON log line includes all MDC fields.

**AsyncAppender:** Background thread handles file I/O. Prevents logging from blocking request processing threads.

## 4. Key Code Explained

```xml
<encoder class="net.logstash.logback.encoder.LogstashEncoder">
    <customFields>{"application":"${APP_NAME}"}</customFields>
    <!-- traceId: automatically set by Micrometer Tracing in MDC -->
    <includeMdcKeyName>traceId</includeMdcKeyName>
    <includeMdcKeyName>customerId</includeMdcKeyName>
</encoder>

<appender name="ASYNC_FILE" class="ch.qos.logback.classic.AsyncAppender">
    <queueSize>512</queueSize>
    <!-- Drop TRACE/DEBUG when queue is 80% full. Keep ERROR/WARN flowing. -->
    <discardingThreshold>20</discardingThreshold>
</appender>
```

## 5. Common Mistakes
| Mistake | Consequence |
|---|---|
| MDC.clear() missing | Next request inherits previous request's customer context |
| No AsyncAppender | Disk I/O blocks request threads → latency spikes |
| Logging PII | RBI violation, security breach |

## 6. Interview Questions
1. What is MDC? Why is MDC.clear() critical in a finally block?
2. Why use JSON logging for production systems?
3. What is the difference between ConsoleAppender and RollingFileAppender?
4. Why use AsyncAppender? What is the trade-off?
5. How does traceId get into every log line automatically?

---

# ═══════════════════════════════════════════════════════════════════
# FILE 4: PricingMetrics.java — Custom Micrometer Metrics
# ═══════════════════════════════════════════════════════════════════

## 1. Purpose
Defines all custom business metrics for the ePricing domain.

## 2. Why This File Exists
Spring Boot auto-collects JVM, HTTP, HikariCP metrics. These tell you if the JVM is healthy. They do NOT tell you if the pricing BUSINESS is healthy. Custom metrics answer business questions.

## 3. Metric Types

**Counter:** Only goes up. Use for events.
```
pricing_requests_total{type="success"} 1250
```
In Grafana: `rate(pricing_requests_total[5m])` = requests per second

**Gauge:** Can go up or down. Use for current state.
```
pricing_requests_active 7
```
Spikes and stays high = requests are piling up (possible deadlock).

**Timer:** Records duration AND count.
```
pricing_calculation_duration_seconds_count 1297
pricing_calculation_duration_seconds_sum   12.45
```
`histogram_quantile(0.95, ...)` = p95 latency.

**DistributionSummary:** Like Timer but for non-time values.
```
pricing_loan_amount_requested_rupees_bucket{le="1000000"} 450
```
`histogram_quantile(0.50, ...)` = median loan amount.

## 4. Cardinality Rule
NEVER use a tag with unbounded unique values as a Micrometer tag.
Good: `product_type=HOME_LOAN` (5 unique values)
Bad: `customerId=CUST001234` (millions of unique values → Prometheus memory explosion)

## 5. Interview Questions
1. What is the difference between a Counter and a Gauge?
2. Why do we need publishPercentileHistogram(true) for p95/p99?
3. What is label cardinality? Why does it matter for Prometheus?
4. What does Timer.Sample.start() / sample.stop(timer) do?
5. Why implement MeterBinder instead of injecting MeterRegistry directly?

---

# ═══════════════════════════════════════════════════════════════════
# FILE 5: PricingService.java — Business Orchestrator with Full Observability
# ═══════════════════════════════════════════════════════════════════

## 1. Purpose
Business logic orchestrator that weaves metrics, logs, and traces into every operation.

## 2. The Observability Trinity in One Method

```java
// METRICS
Timer.Sample sample = pricingMetrics.startCalculationTimer();
pricingMetrics.recordPricingRequestReceived();

// TRACES
Span span = tracer.spanBuilder("calculatePricing")
    .setAttribute("customer.id", customerId)
    .startSpan();

// LOGS
structuredLogger.logPricingStarted(customerId, productType, amount);
```

All three signals share the same traceId — correlatable in Grafana.

## 3. Custom Child Spans Value
Auto-instrumentation creates span for `GET /api/v1/pricing`. Child spans reveal:
```
GET /api/v1/pricing (150ms)
  validateEligibility (5ms)
  computeInterestRate (10ms)
  persistPricingRequest (120ms) ← DB is the bottleneck!
```
Without child spans, you know the request took 150ms but not WHY.

## 4. @Transactional
```java
@Transactional
public PricingResponseDto calculatePricing(...) {
    // ALL database operations are in ONE transaction
    // If ANY step fails → ALL database changes rolled back
}
```

`Propagation.REQUIRES_NEW` in PricingAuditService: Creates SEPARATE transaction. If main transaction rolls back, audit record is still saved (evidence of the attempt).

## 5. Interview Questions
1. What is @Transactional? What happens if an exception is thrown inside?
2. What is Propagation.REQUIRES_NEW? When would you use it?
3. Why create custom child spans?
4. Why is constructor injection preferred over @Autowired field injection?
5. What is pricingSpan.end() in the finally block? What happens if forgotten?

---

# ═══════════════════════════════════════════════════════════════════
# FILE 6: Dockerfile — Container Image Recipe
# ═══════════════════════════════════════════════════════════════════

## 1. Purpose
Instructions to build a self-contained Docker image with the application and Java runtime.

## 2. Multi-Stage Build

Stage 1 (builder): Maven + JDK compiles and packages → only the JAR matters
Stage 2 (runtime): JRE only (180MB vs 400MB JDK) → smaller, more secure

## 3. Layered JAR Caching

```dockerfile
COPY dependencies/ ./           # Layer 1: stable — only changes with pom.xml
COPY spring-boot-loader/ ./     # Layer 2: very stable
COPY application/ ./            # Layer 3: changes every commit
```

Code change → only Layer 3 rebuilds. Layers 1 and 2 are cached. Build time: 30s vs 5 minutes.

## 4. JVM Container Flags

```dockerfile
ENV JAVA_OPTS="-XX:+UseContainerSupport    # Read CPU/RAM from Docker cgroups
               -XX:MaxRAMPercentage=75.0   # Heap = 75% of container limit
               -XX:+UseG1GC               # Low-latency GC
               -Djava.security.egd=file:/dev/./urandom"  # Prevent startup delay
```

Without UseContainerSupport: JVM allocates heap based on HOST RAM (128GB), not container limit (512MB). OOM-killed immediately.

## 5. Interview Questions
1. What is a multi-stage Docker build?
2. Why run as a non-root user in Docker?
3. What does -XX:+UseContainerSupport do?
4. What is a Docker layer? Why does layer order matter?
5. What does EXPOSE do in a Dockerfile?

---

# ═══════════════════════════════════════════════════════════════════
# FILE 7: docker-compose.yml — Full Stack Orchestration
# ═══════════════════════════════════════════════════════════════════

## 1. Purpose
Orchestrates 6 services in one file. `docker-compose up --build` starts the entire observability stack.

## 2. Services

| Service | Port | Purpose |
|---|---|---|
| epricing-service | 8080/8081 | Spring Boot API + Actuator |
| prometheus | 9090 | Metrics storage + scraping |
| grafana | 3000 | Dashboards, visualization |
| loki | 3100 | Log storage |
| promtail | 9080 | Log shipping agent |
| otel-collector | 4317/4318 | Telemetry pipeline |

## 3. Internal DNS
Services find each other by name: `epricing-service:8081`, `loki:3100`, `prometheus:9090`.
Docker's built-in DNS resolves service names to container IPs automatically.

## 4. Interview Questions
1. What is the difference between service_healthy and service_started in depends_on?
2. What is a Docker named volume? What happens with docker-compose down?
3. Why does Prometheus use epricing-service:8081 not localhost:8081?
4. What does deploy.resources.limits.memory: 512M do?
5. What is restart: unless-stopped?

---

# ═══════════════════════════════════════════════════════════════════
# COMPLETE OBSERVABILITY FLOW REFERENCE
# ═══════════════════════════════════════════════════════════════════

## METRICS FLOW
```
Your Code (PricingMetrics.java)
    → Counter.builder("pricing.requests.total").register(registry)
    → Micrometer MeterRegistry collects the metric
    → PrometheusMeterRegistry formats it as Prometheus text
    → /actuator/prometheus endpoint exposes it (port 8081)
    → Prometheus scrapes every 15s (prometheus.yml scrape_configs)
    → Prometheus stores in TSDB
    → Grafana queries with PromQL
    → Dashboard panel displays result
```

## LOGS FLOW
```
Your Code (log.info("message"))
    → SLF4J API passes to Logback
    → MDCFilter has already set MDC: {traceId, requestId, customerId}
    → LogstashEncoder serializes to JSON with all MDC fields included
    → AsyncAppender writes to /app/logs/epricing-service.log
    → Docker volume shares this file with Promtail container
    → Promtail reads file (pipeline stages: json→labels→timestamp→output)
    → Promtail ships to Loki via HTTP push (loki:3100/loki/api/v1/push)
    → Loki stores log chunk with label index
    → Grafana queries with LogQL
    → Logs panel displays results
```

## TRACES FLOW
```
HTTP Request arrives at Spring Boot
    → OTel Spring MVC instrumentation creates ROOT span
    → traceId generated (or inherited from incoming traceparent header)
    → MDC populated: traceId, spanId (by Micrometer Tracing bridge)
    → PricingService creates CHILD spans (calculatePricing, validateEligibility, etc.)
    → Span attributes set: customer.id, product.type, calculated.rate
    → Span events recorded: "Eligibility validation passed"
    → Request completes → all spans ended
    → OTel SDK batches spans
    → OTLP exporter sends to otel-collector:4318 (HTTP/protobuf)
    → OTel Collector processes: memory_limiter → batch → resource
    → Collector exports to Grafana Tempo (or debug exporter in dev)
    → Grafana shows distributed trace waterfall
```

---

# ═══════════════════════════════════════════════════════════════════
# COMMON PRODUCTION MISTAKES — MASTER LIST
# ═══════════════════════════════════════════════════════════════════

| Mistake | File | Consequence | Fix |
|---|---|---|---|
| Missing micrometer-registry-prometheus | pom.xml | Silent 404 on /actuator/prometheus | Add dependency |
| Missing MDC.clear() | MDCFilter | Cross-request MDC contamination | Always clear in finally |
| exposure.include: "*" | application.yml | /actuator/heapdump publicly accessible | Explicit whitelist |
| show-sql: true in production | application.yml | Performance + PII leak risk | Always false in prod |
| High cardinality Micrometer tags | PricingMetrics | Prometheus OOM | Use low-cardinality labels |
| Missing pricingSpan.end() | PricingService | Memory leak + incorrect trace | Always end in finally |
| Missing publishPercentileHistogram | application.yml | histogram_quantile returns NaN | Set to true |
| Container running as root | Dockerfile | Security breach blast radius | Non-root user |
| Missing UseContainerSupport JVM flag | Dockerfile | JVM allocates host RAM as heap → OOM | Add flag |
| No AsyncAppender | logback-spring.xml | Log I/O blocks request threads | Wrap FILE in ASYNC |
| Committing application-prod.yml with secrets | git | Credentials exposed | Use Vault/Secrets Manager |
| Large cardinality Loki labels | promtail-config | Loki OOM, high memory usage | Only index low-cardinality fields |
