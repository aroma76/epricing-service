# ePricing Observability — 100 Interview Questions
## Complete Preparation Guide: Junior → Senior → Architect Level

---

> **How to use this guide:** Questions are organized by domain and difficulty. For each question, first answer from memory, then verify against the explanations. The goal is to be able to explain the concept to a room of engineers, not just recite definitions.

---

## SECTION 1: Spring Boot and Actuator (Q1–Q15)

**Q1.** What does `@SpringBootApplication` expand to? Why is it a composite annotation?
> **Answer:** Expands to `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan`. Composite to reduce boilerplate — instead of remembering three annotations, you use one. `@Configuration` allows `@Bean` methods. `@EnableAutoConfiguration` reads META-INF/spring/AutoConfiguration.imports and activates relevant auto-configs. `@ComponentScan` discovers all `@Component`, `@Service`, `@Repository`, `@Controller` in subpackages.

**Q2.** What is Spring Boot Auto-Configuration? Give 3 concrete examples from this project.
> **Answer:** Spring Boot reads META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports on the classpath. Each entry is a conditional configuration class. Example: `PrometheusMeterRegistryAutoConfiguration` activates when `micrometer-registry-prometheus` is on the classpath → creates PrometheusMeterRegistry bean automatically. Other examples: `DataSourceAutoConfiguration` (when H2 is on classpath), `WebMvcAutoConfiguration` (when spring-web is on classpath).

**Q3.** What is Spring Boot Actuator? Name 5 endpoints it provides.
> **Answer:** Actuator exposes operational information about a running application via HTTP or JMX. Endpoints: `/actuator/health` (UP/DOWN status), `/actuator/prometheus` (Prometheus metrics), `/actuator/metrics` (all metrics), `/actuator/info` (build info), `/actuator/env` (all resolved properties), `/actuator/threaddump` (thread dump), `/actuator/heapdump` (JVM heap dump).

**Q4.** Why should Actuator run on a separate management port (8081) instead of the main API port (8080)?
> **Answer:** Port 8080 is customer-facing and may be exposed to the internet via load balancers. Actuator endpoints like `/actuator/heapdump` (downloads entire JVM heap), `/actuator/env` (shows all properties including secrets), `/actuator/shutdown` (kills the app) must NEVER be publicly accessible. Separate port allows network-level firewall rules: "Block 8081 from internet, allow from ops subnet only."

**Q5.** What does `management.endpoints.web.exposure.include: "health,info,prometheus,metrics"` do? What's the danger of `include: "*"`?
> **Answer:** Explicitly whitelists which Actuator endpoints are accessible via HTTP. `"*"` exposes ALL endpoints including dangerous ones: `/actuator/heapdump` (memory dump for data extraction), `/actuator/shutdown` (kills the application), `/actuator/env` (exposes database passwords if they're in properties). In 2022, several companies had data breaches because Actuator was exposed with `"*"`.

**Q6.** What is `ApplicationReadyEvent`? How is it different from `@PostConstruct`?
> **Answer:** `@PostConstruct` fires after a bean is initialized but before the application context is fully started (other beans may not be ready). `ApplicationReadyEvent` fires AFTER the entire application is ready to serve traffic — all beans are initialized, Tomcat is started, and the application is accepting connections. For startup logging and metric initialization, `ApplicationReadyEvent` is safer.

**Q7.** What is `spring.jpa.hibernate.ddl-auto`? What values does it accept?
> **Answer:** Controls how Hibernate manages the database schema. Values:
> - `none`: No schema changes
> - `validate`: Validates schema matches entities (no changes) — use in production
> - `update`: Adds new columns/tables (does NOT drop) — use carefully in staging
> - `create`: Creates schema, drops on restart — use in development only
> - `create-drop`: Creates on startup, drops on shutdown — use in testing/local dev

**Q8.** What is HikariCP? Why does it matter for observability?
> **Answer:** HikariCP is the default connection pool in Spring Boot 2+. It maintains a pool of pre-opened database connections that are reused across requests (opening a JDBC connection takes 50-100ms — too slow for each request). For observability: Micrometer auto-instruments HikariCP and exposes `hikaricp_connections_active`, `hikaricp_connections_idle`, `hikaricp_connections_pending`, `hikaricp_connections_max`. `connections_pending > 0` means the pool is exhausted — requests are waiting for DB connections.

**Q9.** What is `@Value("${spring.application.name}")`? What happens if the property doesn't exist?
> **Answer:** `@Value` injects a property value into a Spring bean field. Spring reads the value from the resolved property sources (application.yml, env vars, etc.). If the property doesn't exist and no default is provided, Spring throws `BeanCreationException: Could not resolve placeholder '${property.name}'`. To provide a default: `@Value("${property.name:defaultValue}")`.

**Q10.** What is OTLP? How does it relate to OpenTelemetry?
> **Answer:** OTLP (OpenTelemetry Protocol) is the native data format and transport protocol for OpenTelemetry. It defines how telemetry data (traces, metrics, logs) is encoded (Protocol Buffers/protobuf or JSON) and transported (gRPC or HTTP). Your Spring Boot app sends OTLP to the OTel Collector. The Collector then translates to backend-specific formats (Jaeger, Prometheus, Loki). OTLP is vendor-neutral — switching from Jaeger to Zipkin only requires changing the Collector exporter, not application code.

**Q11.** What is `spring.jpa.open-in-view`? Should it be enabled or disabled?
> **Answer:** Open Session in View (OSIV) keeps the Hibernate session open during the entire HTTP request, including view rendering. This allows lazy-loading of JPA associations in controllers/templates. However: it means database connections are held for the ENTIRE request (including time spent rendering HTML/JSON). This depletes the connection pool under load. For REST APIs: ALWAYS disable (`false`). For MVC apps rendering Thymeleaf: Consider disabling and using DTOs instead.

**Q12.** What is the difference between `@Service`, `@Repository`, and `@Component`?
> **Answer:** All three are `@Component` specializations that enable component scanning. Functionally identical (Spring creates a singleton bean). Semantic differences: `@Service` = business logic layer (enables AOP for transaction management). `@Repository` = data access layer (Spring translates JDBC exceptions to Spring DataAccessException). `@Component` = generic Spring-managed component. Using the correct annotation makes the code self-documenting and enables AOP pointcuts that target specific layers.

**Q13.** How does `@Transactional` work internally?
> **Answer:** `@Transactional` uses Spring AOP (CGLIB proxy). At startup, Spring wraps the annotated class in a proxy. When you call a `@Transactional` method, the proxy intercepts the call: 1) Opens a database connection, 2) Starts a transaction (BEGIN), 3) Calls the actual method, 4) If no exception: COMMIT, 5) If RuntimeException: ROLLBACK. The proxy uses `TransactionSynchronizationManager` to bind the connection to the current thread so all JPA operations in the method use the same connection.

**Q14.** What is `@Async`? What happens if you call an `@Async` method from within the same class?
> **Answer:** `@Async` causes Spring to execute the method in a separate thread (from the configured thread pool) instead of the calling thread. The calling method returns immediately (for void) or a `Future`/`CompletableFuture`. CRITICAL: Calling `@Async` methods from within the same class does NOT work because Spring's AOP proxy is bypassed — `this.asyncMethod()` calls the real method, not the proxy. Solution: Inject the bean into itself (self-injection) or move the method to a separate class.

**Q15.** What is `@EnableAsync` and why is it required?
> **Answer:** Spring does not process `@Async` annotations by default. `@EnableAsync` activates Spring's asynchronous method execution infrastructure — it creates an AOP advisor that intercepts `@Async` methods and submits them to the configured `Executor`. Without `@EnableAsync`, `@Async` methods run SYNCHRONOUSLY on the calling thread — no error, no warning, just silent failure. Always add it to a `@Configuration` class.

---

## SECTION 2: Micrometer and Custom Metrics (Q16–Q30)

**Q16.** What is Micrometer? How does it relate to Prometheus?
> **Answer:** Micrometer is a vendor-neutral metrics facade (like SLF4J is for logging). You write your metrics code against the Micrometer API (`Counter`, `Timer`, `Gauge`). Micrometer translates to the backend's format. When you add `micrometer-registry-prometheus`, Micrometer stores metrics in a Prometheus-compatible format and exposes them at `/actuator/prometheus`. When you add `micrometer-registry-datadog`, the same metrics are sent to Datadog. Switch backends by changing the dependency — not your business code.

**Q17.** What is the difference between a Counter and a Gauge in Micrometer?
> **Answer:** Counter: Monotonically increasing value. Tracks events. Example: total requests, total errors, total orders. Gauge: Current value that can increase or decrease. Tracks current state. Example: active connections, queue depth, in-flight requests, temperature. In Prometheus, counters are queried with `rate()` to compute per-second rates. Gauges are queried directly.

**Q18.** What is a Timer in Micrometer? What three values does it produce in Prometheus?
> **Answer:** Timer measures the duration of operations. In Prometheus, one Timer produces: `_count` (number of observations), `_sum` (total time across all observations), `_bucket` (histogram buckets with le labels, for percentile calculation). From these: `_sum / _count` = average duration. `histogram_quantile(0.95, rate(_bucket[5m]))` = p95 latency.

**Q19.** What does `publishPercentileHistogram(true)` do? What would happen without it?
> **Answer:** Causes Micrometer to store histogram buckets (le="0.05", le="0.1", etc.) in Prometheus. Without buckets: only `_count` and `_sum` are stored. `histogram_quantile()` requires buckets to compute percentiles. Without `publishPercentileHistogram(true)`: `histogram_quantile(0.95, ...)` returns NaN or no data in Grafana. You lose the ability to measure SLOs (Service Level Objectives) like "95% of requests complete within 500ms."

**Q20.** What is label (tag) cardinality? Why is it critical for Prometheus performance?
> **Answer:** Cardinality = number of unique label value combinations. Each unique combination creates a new time series in Prometheus (separate row in the TSDB). Problem: `counter{customerId="CUST001234"}` — with 10 million customers = 10 million time series. Prometheus stores ALL time series in memory. 10 million time series × ~4KB per series = 40GB RAM just for one metric. This is called the "cardinality bomb." Rule: Tags/labels must have bounded, known, small set of unique values.

**Q21.** What is the `MeterBinder` interface? Why use it for custom metrics?
> **Answer:** `MeterBinder` provides a lifecycle-safe way to register custom metrics. `bindTo(MeterRegistry registry)` is called by Spring after the MeterRegistry is fully initialized. This prevents circular dependency issues (your metrics class depending on the registry before it's ready). It also enables modular metric registration — you can have multiple MeterBinder beans, each registering a subset of metrics.

**Q22.** What is `Timer.Sample.start()` and how do you use it?
> **Answer:** `Timer.Sample.start()` captures the current time. When the operation completes, call `sample.stop(timer)` to record the duration. This pattern avoids exposing the timer to the calling code and is exception-safe:
> ```java
> Timer.Sample sample = Timer.start();
> try {
>     // ... operation ...
> } finally {
>     sample.stop(myTimer); // records duration even if exception thrown
> }
> ```

**Q23.** How does Micrometer automatically add tags to all metrics?
> **Answer:** In `application.yml`: `management.metrics.tags.application: ${spring.application.name}` adds `{application="epricing-service"}` to EVERY metric automatically. This enables Grafana dashboard templates — you can have one dashboard for all services and use a variable `$application` to filter. Without common tags, you'd need separate dashboards for each service.

**Q24.** What is a `DistributionSummary`? When would you use it instead of a Timer?
> **Answer:** `DistributionSummary` records the distribution of non-time values. Use when measuring quantities that aren't durations: loan amounts in rupees, file sizes in bytes, scores, counts. Timer is specialized for duration (has `TimeUnit`). DistributionSummary is for generic numeric values. Example: `DistributionSummary.builder("loan.amount").baseUnit("rupees")` tracks the distribution of loan amounts → `histogram_quantile(0.50, ...)` = median loan amount.

**Q25.** What is the difference between `_count`, `_sum`, `_bucket` in a Timer metric?
> **Answer:** For Timer `pricing.calculation.duration`:
> - `_count`: `pricing_calculation_duration_seconds_count` — number of calculations performed
> - `_sum`: `pricing_calculation_duration_seconds_sum` — total time spent across all calculations
> - `_bucket`: `pricing_calculation_duration_seconds_bucket{le="0.1"}` — how many calculations completed in ≤ 0.1 seconds
> Average = sum/count. P95 = `histogram_quantile(0.95, rate(bucket[5m]))`.

**Q26.** What is the `up` metric in Prometheus? How is it generated?
> **Answer:** Prometheus automatically creates `up{job="...", instance="..."}` for each scrape target. If the scrape succeeds (HTTP 200): `up=1`. If the scrape fails (connection refused, timeout, HTTP 5xx): `up=0`. This is how you alert on service downtime: `alert if up{job="epricing-service"} == 0 for 1m`. The `up` metric is the simplest and most reliable health check.

**Q27.** How does `rate()` function work in PromQL? When to use it vs `increase()`?
> **Answer:** `rate(counter[5m])` = per-second rate of increase over the last 5 minutes. It handles counter resets (service restart = counter resets to 0; rate() detects and compensates). `increase(counter[5m])` = total increase over 5 minutes (not per-second). Use `rate()` for per-second rates in dashboards (request rate, error rate). Use `increase()` for totals ("how many errors happened in the last hour?").

**Q28.** What is `histogram_quantile(0.95, ...)` and what does it require?
> **Answer:** Calculates the 95th percentile (p95) from histogram bucket data. "95% of observations fell below this value." Requirements: 1) The metric must be a histogram with `_bucket` labels. 2) `publishPercentileHistogram(true)` must be set in Micrometer. 3) Must use `rate()` around the bucket metric (not raw bucket values). Formula: `histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le))`.

**Q29.** What is a recording rule in Prometheus? Why use it?
> **Answer:** Recording rules pre-compute expensive PromQL expressions and store the result as a new metric. Example: Compute `rate(http_server_requests_seconds_count[5m])` every 15 seconds and store as `job:http_requests_per_second:rate5m`. Benefits: 1) Dashboard queries execute instantly (read from TSDB vs compute on the fly). 2) Consistent results (all panels use the same pre-computed value). 3) Reduce Prometheus query load. Use for complex queries that multiple dashboards need.

**Q30.** What is the `@Counted` and `@Timed` annotation in Micrometer? Why might you prefer programmatic registration?
> **Answer:** `@Counted` automatically increments a counter on every method call. `@Timed` automatically records the duration. They use Micrometer's Aspect-Oriented Programming (AOP) support. Limitation: You cannot add dynamic tags at runtime (you don't know the product type or outcome before the method runs). Programmatic registration (like in PricingMetrics) allows: 1) Dynamic tags based on runtime values, 2) More control over metric configuration, 3) Easier testing.

---

## SECTION 3: OpenTelemetry and Distributed Tracing (Q31–Q45)

**Q31.** What is distributed tracing? What problem does it solve?
> **Answer:** Distributed tracing tracks a request as it flows through multiple microservices, databases, and external APIs. Without tracing: "Response took 5 seconds — which service caused it?" With tracing: You see a waterfall showing exactly how much time was spent in each service and each operation. Tracing answers: 1) What is the call path for a request? 2) Which service is the bottleneck? 3) Which database query is slow? 4) Where did the error originate in a cascade?

**Q32.** What is a Span? What information does it contain?
> **Answer:** A span represents one unit of work in a distributed system. It contains: `spanId` (unique 8-byte hex), `traceId` (shared by all spans in the same request), `parentSpanId` (creates the hierarchy), `name` (operation name, e.g., "GET /api/v1/pricing"), `start_time`, `end_time`, `attributes` (key-value pairs: "http.method"="POST"), `events` (timestamped annotations within a span), `status` (OK or ERROR), `links` (optional connections to other traces).

**Q33.** What is Context Propagation? How does it work across HTTP calls?
> **Answer:** Context propagation passes the trace context from one service to another via HTTP headers. Standard: W3C TraceContext. Header: `traceparent: 00-{traceId}-{parentSpanId}-{flags}`. When your service calls another service, the OTel SDK automatically injects this header into the outgoing request. The downstream service reads the header and creates a child span with the same traceId. Without propagation: each service has its own disconnected traces.

**Q34.** What is the difference between head-based and tail-based sampling?
> **Answer:** Head-based sampling: The decision to sample is made at the START of the trace, at the first span. Simple to implement. Problem: You don't know if the request will error or be interesting. You may sample boring successful requests and miss errors. Tail-based sampling: The decision is made AFTER the trace is complete. You can ensure ALL errors are sampled (even at 1% overall rate). More complex — requires buffering complete traces before exporting. OTel Collector supports tail-based sampling.

**Q35.** What is the OTel Collector? Why is it architecturally important?
> **Answer:** The OTel Collector is a standalone service that receives telemetry via OTLP, processes it (filter, batch, enrich), and exports to multiple backends. Without it: app has N exporters (Jaeger, Prometheus, Loki). With it: app has ONE OTLP exporter. The collector handles routing. Key benefits: 1) Vendor neutrality — switch from Jaeger to Zipkin without code changes. 2) Centralized processing — apply sampling, filtering, PII redaction in one place. 3) Buffering — handles backend downtime gracefully.

**Q36.** What is `Span.current()` in OpenTelemetry? How do you use it?
> **Answer:** `Span.current()` returns the currently active span for the current thread. Used to: 1) Add attributes to the current span without injecting the Tracer. 2) Get the traceId for logging. 3) Set error status. Example:
> ```java
> Span.current().setAttribute("user.id", userId);
> Span.current().setStatus(StatusCode.ERROR, "Calculation failed");
> Span.current().recordException(exception);
> String traceId = Span.current().getSpanContext().getTraceId();
> ```

**Q37.** What is `Scope.makeCurrent()` in OTel? What happens if you forget to close it?
> **Answer:** `scope.makeCurrent()` sets a span as the "current" span for the current thread using a thread-local. When you create a child span, it needs to know its parent — it reads the "current" span from the thread-local. If you forget to close the scope: 1) The parent-child span relationship is corrupted. 2) The old span remains "current" indefinitely. 3) Memory leak (scope holds a reference to the span). Always use try-with-resources: `try (Scope scope = span.makeCurrent()) { ... }`.

**Q38.** What are Span attributes vs Span events?
> **Answer:** **Attributes:** Key-value pairs describing the span's context. Set at span creation or during execution. Indexed by tracing backends for filtering. Examples: `"http.method"="POST"`, `"db.statement"="SELECT..."`, `"user.id"="CUST001234"`. **Events:** Time-stamped annotations within a span. Record interesting moments during execution. NOT indexed. Examples: `span.addEvent("Eligibility validation passed")`, `span.addEvent("Cache miss, fetching from DB")`. Use attributes for filterable metadata, events for timeline annotations.

**Q39.** What is `span.recordException(ex)` vs `span.setStatus(ERROR, message)`?
> **Answer:** `recordException(ex)`: Records the exception as a Span event with the stack trace. Creates an event with attributes: exception.type, exception.message, exception.stacktrace. Does NOT change the span's status (span can still be OK while having a recorded exception). `setStatus(ERROR, message)`: Marks the span itself as errored. The status appears as RED in Grafana Tempo's trace view. Best practice: call BOTH for exception handling:
> ```java
> span.setStatus(StatusCode.ERROR, ex.getMessage());
> span.recordException(ex);
> ```

**Q40.** What is W3C TraceContext? What does the `traceparent` header look like?
> **Answer:** W3C TraceContext is the standard for propagating trace context across HTTP requests. Format: `traceparent: {version}-{traceId}-{parentSpanId}-{flags}`. Example: `traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01`. Version `00`, traceId `4bf92f3...` (32 hex chars = 16 bytes), parentSpanId `00f067...` (16 hex chars = 8 bytes), flags `01` (sampled=true). Replaces vendor-specific headers (Zipkin's B3, Jaeger's uber-trace-id).

**Q41.** What is Grafana Tempo? How does it differ from Prometheus?
> **Answer:** Grafana Tempo is a distributed tracing backend (stores spans and traces). Prometheus is a metrics store (stores numeric time-series). Key differences: Tempo stores traces (event data, high cardinality, one per request). Prometheus stores metrics (aggregated numeric data, many requests aggregated into one number). In the Grafana stack: Prometheus = metrics → numbers over time. Loki = logs → text over time. Tempo = traces → request journeys. All three linked by traceId.

**Q42.** What is Exemplar? How does it link metrics to traces?
> **Answer:** An Exemplar is a specific example trace attached to a metric data point. When Prometheus scrapes a metric like `http_server_requests_seconds_count`, it can also capture the traceId of a recent request that contributed to that count. In Grafana: You see a spike in the error rate graph. You click the spike. Grafana uses the Exemplar (traceId) to jump directly to the trace for a request that caused the spike. This closes the gap between metrics ("something is wrong") and traces ("why it's wrong").

**Q43.** What is the difference between `AutoConfiguredOpenTelemetrySdk` and manually building the SDK?
> **Answer:** `AutoConfiguredOpenTelemetrySdk` reads configuration from environment variables and system properties following the OTel specification (OTEL_SERVICE_NAME, OTEL_EXPORTER_OTLP_ENDPOINT, etc.). This is the recommended approach because: 1) Operators can change tracing configuration without code changes. 2) Consistent behavior with other OTel-instrumented services. 3) Follows the 12-factor app principle (config via environment). Manual SDK construction requires hardcoding configuration in Java code.

**Q44.** What is Baggage in OpenTelemetry?
> **Answer:** Baggage is key-value pairs that propagate across service boundaries alongside the trace context. Unlike span attributes (only visible in the current span's data), baggage is carried forward to every downstream service. Example: Set `baggage.customerId = "CUST001234"` in Service A. Service B can read this baggage without Service A explicitly passing it in the API payload. Use cases: A/B test group ID, feature flags, request source identifier. CAUTION: Baggage travels in headers — keep it small and never put sensitive data in Baggage.

**Q45.** What is a No-Op Span? When does OTel return one?
> **Answer:** When a span's trace context is invalid (not sampled, or OTel not properly initialized), OTel returns a No-Op span instead of null. `Span.getInvalid()` or `NOOP_SPAN`. All methods on a No-Op span are no-ops (do nothing). This prevents NullPointerExceptions in your code — you don't need null checks before `span.setAttribute(...)`. If you see `traceId="00000000000000000000000000000000"` in logs, OTel is not properly configured.

---

## SECTION 4: Logging, Loki, and Promtail (Q46–Q60)

**Q46.** What is the difference between SLF4J and Logback?
> **Answer:** SLF4J (Simple Logging Facade for Java) is a LOGGING API — a set of interfaces your code calls (`log.info()`, `log.error()`). Logback is a LOGGING IMPLEMENTATION that implements the SLF4J API. The facade pattern means your code doesn't depend on the specific implementation. If you want to switch from Logback to Log4j2: change the dependency — not your code. Spring Boot uses Logback by default because it's the successor to Log4j (by the same author) and is included in `spring-boot-starter-logging`.

**Q47.** What is MDC (Mapped Diagnostic Context)? How is it thread-safe?
> **Answer:** MDC is a thread-local map that SLF4J provides. Thread-local means each thread has its own copy of the map — no concurrency issues. When Thread A handles Request 1 and sets `MDC.put("customerId", "CUST001")`, Thread B handling Request 2 has a separate MDC map and sets `MDC.put("customerId", "CUST002")`. They don't interfere. The logger's encoder includes ALL MDC entries in every log statement produced on that thread. Thread-local is automatically cleaned when the thread returns to the pool IF you call `MDC.clear()`.

**Q48.** Why is structured logging (JSON) better than plain text for production systems?
> **Answer:** Plain text: `2024-01-15 INFO - Pricing calculated for CUST001 at 8.75%`. Machine parsing requires fragile regex. Loki cannot extract `customerId` without knowing the exact log format. JSON: `{"level":"INFO","customerId":"CUST001","rate":"8.75","event":"PRICING_CALCULATED"}`. Machines parse it reliably. Loki extracts labels. Grafana lets you filter, aggregate, and alert on specific fields. In production with 1M logs/day, structured logging is the difference between finding a bug in 5 minutes vs 5 hours.

**Q49.** What is Loki? What makes it different from Elasticsearch?
> **Answer:** Loki is a horizontally-scalable log aggregation system optimized for labels rather than full-text search. Elasticsearch indexes EVERY word in EVERY log → high storage and compute. Loki indexes only LABELS (low cardinality structured fields) and stores log content compressed. For a service generating 100MB/day of logs: Elasticsearch needs ~1GB storage (indexed). Loki needs ~10MB (compressed, minimally indexed). For banking at scale (100 services, 1TB/day): Elasticsearch = prohibitively expensive. Loki = cost-effective.

**Q50.** What is Promtail? What is a pipeline stage?
> **Answer:** Promtail is the log shipping agent for Loki. It reads log files (like `tail -f` but persistent), processes them through a pipeline, and pushes to Loki. A pipeline stage is one processing step: `json` (parse JSON → extract fields), `labels` (promote fields to Loki labels), `timestamp` (set log timestamp from content), `output` (set the log line content), `regex` (parse with regex), `template` (transform values), `drop` (filter out unwanted log lines), `replace` (modify field values).

**Q51.** Why does the positions file in Promtail matter?
> **Answer:** Promtail records the last-read position (byte offset) in each log file to `/tmp/positions.yaml`. On restart, Promtail reads this file and continues from where it stopped instead of re-reading from the beginning. Without positions.yaml: Every Promtail restart → re-reads all log files → sends duplicate log entries to Loki → Grafana shows duplicate logs → debugging becomes confusing. In production: Mount positions.yaml on a persistent volume (not /tmp which is ephemeral in containers).

**Q52.** What is LogQL? Write a query to find all ERROR logs for the epricing-service.
> **Answer:** LogQL is Loki's query language (like PromQL but for logs). Query: `{application="epricing-service"} | json | level = "ERROR"`. Breakdown: `{application="epricing-service"}` = stream selector (filter by label). `| json` = parse JSON log lines. `| level = "ERROR"` = filter on extracted JSON field. More specific: `{application="epricing-service"} | json | level = "ERROR" | customerId = "CUST001234"` → all errors for specific customer.

**Q53.** What is label cardinality in Loki? What makes a bad label?
> **Answer:** Loki stores labels in an inverted index in memory. High cardinality labels mean millions of unique index entries. `customerId` with 10M unique values → 10M index entries × memory overhead = Loki OOM. Rule: Use labels only for fields with a small, bounded set of unique values. Good labels: `level` (5 values: TRACE/DEBUG/INFO/WARN/ERROR), `application` (50 services), `environment` (3: dev/staging/prod). Bad labels: `requestId` (billions), `customerId` (millions), `timestamp` (infinite).

**Q54.** What is the difference between a label selector and a filter expression in LogQL?
> **Answer:** Label selector `{application="epricing-service"}` is evaluated against Loki's INDEX (fast — uses the inverted index). Filter expression `| level = "ERROR"` is evaluated against LOG CONTENT (slow — requires reading chunk files). Best practice: Be as specific as possible in the label selector to reduce the number of chunks Loki reads. Then apply filter expressions on the smaller result set. Never use filter expressions alone without a label selector.

**Q55.** How does Promtail's timestamp stage prevent log ordering issues?
> **Answer:** Without timestamp stage: Loki uses the TIME PROMTAIL RECEIVED the log as the timestamp. If your app logs at 10:30:00 but Promtail reads the file 5 seconds later, Loki stores the log at 10:30:05. If you're doing post-incident analysis for an outage at 10:30:00, the logs appear 5 seconds after the outage. With timestamp stage: Promtail reads the timestamp from the JSON log content (the actual time the log was written: 10:30:00). Grafana shows accurate timing.

**Q56.** What is AsyncAppender in Logback? What is the risk?
> **Answer:** AsyncAppender puts log events in an in-memory queue and a background thread reads from the queue and writes to the actual output (file, network). Without async: `log.info(...)` → waits for disk I/O → continues. Slow disk = slow request handling. With async: `log.info(...)` → adds to queue → returns immediately. Risk: If the application crashes, log events in the queue are lost (not written to disk). For audit logs: use synchronous writing to never lose events. For operational logs: async is acceptable — losing a few INFO logs during a crash is tolerable.

**Q57.** What is log rotation? Why is it important?
> **Answer:** Log rotation replaces the current log file with a new one on a schedule (daily) or size threshold (100MB). Without rotation: A log file grows indefinitely. After 1 year of continuous operation: `epricing-service.log` = 500GB. The disk fills up. The application cannot write logs (disk full). Garbage collection of old logs fails. With rotation: Files are limited to 100MB. Old files are compressed (.gz, 90% compression ratio). Files older than 30 days are auto-deleted. In Spring Boot (Logback): `maxFileSize: 100MB`, `maxHistory: 30`, `totalSizeCap: 1GB`.

**Q58.** What is a log aggregation system? Name alternatives to Loki.
> **Answer:** Log aggregation collects logs from multiple sources, stores them, and provides search/analysis capabilities. Loki alternatives: Elasticsearch/OpenSearch + Kibana (ELK stack) — feature-rich, expensive. Splunk — enterprise-grade, very expensive. Datadog Logs — managed SaaS, simple setup, expensive at scale. Fluentd/Fluentbit (just the shipping agent, like Promtail). AWS CloudWatch Logs — native AWS, good if already on AWS. Loki's advantage: cost (stores 10x less data than Elasticsearch) and native Grafana integration.

**Q59.** How would you implement log-based alerting in Loki?
> **Answer:** Loki's Ruler component evaluates LogQL queries periodically and fires alerts to Alertmanager. Example rule: "If error count > 10 in 5 minutes → alert". LogQL metric query: `count_over_time({application="epricing-service"} | json | level="ERROR" [5m]) > 10`. Configure in Loki's ruler config. Alertmanager routes to Slack/PagerDuty. Useful for: 1) Alerting on specific error messages not captured by metrics. 2) Pattern matching in log content (e.g., specific exception class names).

**Q60.** What is the difference between Promtail, Fluentd, and Filebeat?
> **Answer:** All are log shipping agents. Promtail: Designed specifically for Loki (tight integration, native label model). Lightweight (~30MB). Fluentd: Mature, plugin-rich (500+ plugins for any source/destination). Higher memory usage. Used in Kubernetes (DaemonSet). Filebeat: Elastic's log shipper (ELK stack focused). Lightweight, efficient. OpenTelemetry Collector (OTLP Logs): The emerging standard — one agent for logs, metrics, AND traces. For Loki: Promtail is the natural choice. For multi-destination routing: Fluentd or OTel Collector.

---

## SECTION 5: Docker, Containers, and Infrastructure (Q61–Q75)

**Q61.** What is a Docker container? How does it differ from a VM?
> **Answer:** A container is a process with isolated namespaces (filesystem, network, PID). It shares the HOST kernel — no guest OS. A VM has its own kernel and guest OS. Size: VM = 2-20GB (includes full OS). Container = 50-500MB (just app + libs). Startup: VM = minutes (boot OS). Container = milliseconds (start a process). Resource isolation: VMs use hypervisor. Containers use Linux cgroups (resource limits) and namespaces (isolation). For microservices: containers are far more efficient. For complete OS isolation: VMs.

**Q62.** What is a Docker image vs a Docker container?
> **Answer:** Image = Template (like a class in OOP). Read-only filesystem snapshot. Built by Dockerfile. Stored in registries (Docker Hub, ECR). Container = Running instance (like an object in OOP). Created from an image. Has a writable layer on top of the image's read-only layers. Multiple containers can be created from the same image (each gets its own writable layer). Example: 10 replicas of epricing-service = 10 containers, all using the same image.

**Q63.** What is a multi-stage Docker build? What problem does it solve?
> **Answer:** Multi-stage build uses multiple FROM statements. Each FROM starts a new stage. Only files explicitly COPY --from=stage are carried forward to the next stage. Problem it solves: "I need Maven + JDK to compile, but I only need JRE to run." Without multi-stage: Final image includes Maven (100MB), JDK (300MB), source code, test classes — unnecessary and insecure. With multi-stage: Final image only has JRE + compiled classes. Result: 80MB image vs 500MB+ single-stage.

**Q64.** What is a Docker layer? Why does layer order matter in Dockerfile?
> **Answer:** Each instruction in a Dockerfile creates a new layer (read-only). Docker caches layers. If a layer's content hasn't changed, Docker uses the cached version (skips re-execution). Layer invalidation: If Layer N changes, all layers after N must be rebuilt. This is why we COPY pom.xml BEFORE COPY src/: pom.xml changes rarely (only when dependencies change). src/ changes on every code commit. By copying pom.xml first and running `mvn dependency:resolve`, we cache the 200MB dependency download layer and only rebuild the 1MB application layer on code changes.

**Q65.** What is `-XX:+UseContainerSupport`? What happened before Java 10?
> **Answer:** Before Java 10: JVM read `total system RAM` from `/proc/meminfo` (host memory) instead of the container's cgroup memory limit. If your container limit is 512MB but the host has 128GB: JVM allocates `128GB × 25% = 32GB heap`. Container gets OOM-killed immediately. With `-XX:+UseContainerSupport` (default in Java 11+, opt-in for Java 8u191+): JVM reads memory from cgroups (`/sys/fs/cgroup/memory/memory.limit_in_bytes`). Container limit is 512MB → JVM allocates `512MB × 75% = 384MB heap`. Correct behavior.

**Q66.** What does `docker-compose up --build` do?
> **Answer:** Reads `docker-compose.yml`. For each service with a `build:` section: runs `docker build` to build/rebuild the image. Then starts all services in dependency order (respecting `depends_on`). Attaches to container logs and streams to terminal. Without `--build`: Uses cached/existing images (may use stale code). With `--build`: Always builds fresh images from Dockerfiles. In development: always use `--build` to ensure your latest code is running.

**Q67.** What is a Docker named volume? How does it differ from a bind mount?
> **Answer:** Named volume: `prometheus-data:`. Docker manages the storage location (on host at `/var/lib/docker/volumes/`). Data persists across container restarts and `docker-compose down`. `docker-compose down -v` deletes volumes. Bind mount: `./prometheus.yml:/etc/prometheus/prometheus.yml:ro`. Maps a specific host file/directory to a container path. Used for: config files (edit on host, container reads), log sharing (container writes logs, Promtail reads). Named volumes for persistent data. Bind mounts for config files and shared directories.

**Q68.** What is Docker's internal DNS? How do services find each other?
> **Answer:** Docker creates a built-in DNS server for each docker-compose network. Services are registered by their service name. When `prometheus` container wants to connect to `epricing-service:8081`, Docker DNS resolves `epricing-service` to the container's IP. If the container restarts and gets a new IP, DNS is updated automatically. No hardcoded IPs needed. In `prometheus.yml`: `targets: ['epricing-service:8081']` works because Docker DNS resolves the hostname. `localhost:8081` would NOT work — Prometheus's container's localhost is NOT the epricing container.

**Q69.** What does `HEALTHCHECK` in Dockerfile do?
> **Answer:** Defines how Docker checks if the container is healthy. Docker runs the command periodically:
> ```dockerfile
> HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
>     CMD wget --spider http://localhost:8081/actuator/health
> ```
> States: starting (while start_period), healthy (command succeeds), unhealthy (command fails retries times). In docker-compose: `depends_on: condition: service_healthy` waits until the dependent service is healthy before starting. Without HEALTHCHECK: Docker considers the container healthy as soon as the process starts (even if app is still initializing).

**Q70.** What is `restart: unless-stopped` in docker-compose?
> **Answer:** Defines the restart policy for a container. Options: `no` (never restart), `always` (always restart, including on docker host boot), `on-failure` (restart only if exit code is non-zero), `unless-stopped` (always restart EXCEPT if manually stopped with `docker stop`). For development: `unless-stopped` — if your app crashes, it restarts automatically. You can still manually stop it. For production: `always` for critical services (ensures they restart on host reboot). In Kubernetes: restart policy is handled by the Pod specification.

**Q71.** What is `deploy.resources.limits.memory: 512M` in docker-compose?
> **Answer:** Sets a cgroup memory limit on the container. Docker kills the container with OOM (Out of Memory) if it tries to use more than 512MB. This prevents a single container from consuming all host memory and starving other services. `reservations.memory: 256M` is a scheduling hint — Docker tries to run this container on a host with at least 256MB available. In production Kubernetes: translated to `resources.limits.memory` and `resources.requests.memory` in the Pod spec.

**Q72.** What is the principle of least privilege for containers?
> **Answer:** Containers should run with the minimum permissions required for their task. Practices: 1) Non-root user (`USER epricing`). 2) Read-only root filesystem (`--read-only` flag). 3) Drop Linux capabilities (`--cap-drop=ALL --cap-add=NET_BIND_SERVICE`). 4) No privileged mode (`--privileged=false`). 5) No host namespace sharing. Why: If an attacker exploits an app vulnerability and gets code execution, their blast radius is limited to the `epricing` user's permissions — not root access to the host.

**Q73.** What is Docker Compose vs Kubernetes?
> **Answer:** Docker Compose: Single-host orchestration. All services run on one machine. Simpler (one YAML file). Best for: development, testing, small deployments. Kubernetes: Multi-host orchestration. Containers distributed across a cluster of nodes. Built-in: auto-scaling, rolling deployments, self-healing, service discovery, load balancing. Best for: production at scale. This project uses Docker Compose for local development. In Bank production: the same Docker images run on Kubernetes (EKS/AKS).

**Q74.** What is a Docker Registry? What is the difference between Docker Hub and a private registry?
> **Answer:** A Docker Registry is a storage and distribution system for Docker images. `docker push image:tag` uploads to registry. `docker pull image:tag` downloads. Docker Hub: Public registry (hub.docker.com). Free for public images. Rate-limited for private images. Used for: prom/prometheus, grafana/grafana, eclipse-temurin. Private Registry: Nexus, JFrog Artifactory, AWS ECR, Google GCR. Hosted by your organization. Used for: your application images (epricing-service:1.0.0). Security: Private registry ensures your code is never uploaded to a public location.

**Q75.** What is `docker logs epricing-service` vs reading log files?
> **Answer:** `docker logs epricing-service` reads from the container's stdout/stderr. Our app has two logging targets: 1) Console (stdout → captured by Docker) and 2) File (`/app/logs/epricing-service.log`). `docker logs` shows console output in real-time without entering the container. Reading log files requires either `docker exec -it epricing-service cat /app/logs/...` or mounting the volume. For observability: Promtail reads the file (not docker logs). Why: log files support rotation, Promtail's position tracking, and label extraction via pipeline stages.

---

## SECTION 6: Prometheus Alerting and PromQL (Q76–Q85)

**Q76.** What is PromQL? Write a query to calculate the HTTP error rate percentage.
> **Answer:** PromQL (Prometheus Query Language) is the functional query language for Prometheus. Error rate query:
> ```promql
> (
>   rate(http_server_requests_seconds_count{job="epricing-service", status=~"5.."}[5m])
>   /
>   rate(http_server_requests_seconds_count{job="epricing-service"}[5m])
> ) * 100
> ```
> `status=~"5.."` = regex matching any 5xx status code.

**Q77.** What is the difference between PENDING and FIRING alert states in Prometheus?
> **Answer:** When an alert condition becomes true, it enters PENDING state (not yet fired). It stays in PENDING for the `for` duration (e.g., `for: 3m`). If still true after 3 minutes → FIRING (alert sent to Alertmanager). If condition becomes false during PENDING → back to INACTIVE. This prevents flapping alerts from transient issues. A service that goes down for 30 seconds and comes back should not wake up an on-call engineer at 3 AM.

**Q78.** What is Alertmanager? What does it do that Prometheus doesn't?
> **Answer:** Prometheus fires alerts to Alertmanager. Alertmanager handles: 1) Routing: "Send PagerDuty alerts to the epricing team, Slack alerts to #monitoring". 2) Deduplication: Multiple Prometheus instances firing the same alert → one notification. 3) Grouping: 50 related alerts (all services down) → one grouped notification. 4) Silencing: Maintenance window → silence alerts for 2 hours. 5) Inhibition: If "cluster is down" alert fires, suppress all individual service alerts. Prometheus only evaluates rules and fires. Alertmanager handles the notification logic.

**Q79.** What are Prometheus recording rules? Write an example.
> **Answer:** Recording rules pre-compute PromQL expressions and store results as new metrics:
> ```yaml
> groups:
>   - name: epricing.rules
>     rules:
>       - record: job:http_requests_per_second:rate5m
>         expr: rate(http_server_requests_seconds_count{job="epricing-service"}[5m])
> ```
> Now Grafana queries `job:http_requests_per_second:rate5m` instead of computing the rate on the fly. Benefits: Dashboard loads instantly. Multiple panels share the same pre-computed value. Reduces Prometheus query load.

**Q80.** What is the `vector()` function in PromQL?
> **Answer:** `vector(n)` returns a vector with a single element with value n. Used in alert conditions: `vector(0)` acts as a baseline. Example: `error_rate > vector(0.05)` is equivalent to `error_rate > 0.05` but allows using the threshold as a metric reference (useful in "or vector(0)" patterns for handling "no data" cases). `vector(1)` can represent the "true" condition in boolean contexts.

**Q81.** What is the cardinality problem in Prometheus? How would you detect it?
> **Answer:** Cardinality = number of unique time series. Each unique label combination = one time series. High cardinality = high memory usage (Prometheus stores all active time series in RAM). Detection: 1) `prometheus_tsdb_head_series` metric → number of active series. 2) `prometheus_tsdb_head_chunks` → storage usage. 3) `topk(10, count by (__name__, job) ({__name__=~".+"}))` → find which metrics have most series. 4) Check for labels with many unique values using the Prometheus web UI `/tsdb-status` endpoint.

**Q82.** What is `scrape_interval` vs `evaluation_interval` in Prometheus?
> **Answer:** `scrape_interval`: How often Prometheus fetches metrics from targets (e.g., every 15s). Affects data freshness and scrape load on targets. `evaluation_interval`: How often Prometheus evaluates alerting and recording rules (e.g., every 15s). Affects alert response time and recording rule freshness. Both are global defaults but can be overridden per scrape_config or rule group. Typical production: scrape_interval=30s, evaluation_interval=30s. For low-latency alert detection: evaluation_interval=15s.

**Q83.** What is `irate()` vs `rate()` in PromQL?
> **Answer:** `rate(counter[5m])`: Average per-second rate across the entire 5-minute window. Smoothed — less sensitive to individual spikes. Recommended for dashboards. `irate(counter[5m])`: Instantaneous rate — uses the last two samples in the window. Very sensitive to spikes. Use when you need to detect very short-lived spikes. For alerting: use `rate()` (less alert noise). For debugging spike causes: use `irate()`.

**Q84.** How does Prometheus handle counter resets?
> **Answer:** When a service restarts, counters reset to 0. `rate()` detects resets: if the current value < previous value, a reset occurred. Prometheus compensates by computing: `(current_value - previous_value + reset_amount)`. This is why `rate()` is safe for services that restart. If you use raw counter values: restart at 10:30 → counter goes from 15,000 to 0 → graph shows a cliff. `rate()` handles this correctly and shows the actual rate without the cliff.

**Q85.** What is Federation in Prometheus? Why is it used?
> **Answer:** Prometheus Federation allows one "global" Prometheus to scrape metrics from multiple "local" Prometheus instances. Use cases: 1) Multi-datacenter: Each data center has its own Prometheus. Global Prometheus aggregates cross-DC metrics. 2) Scale: If one Prometheus can't handle all targets, shard: Prometheus A scrapes services 1-50, Prometheus B scrapes 51-100. Global Prometheus scrapes A and B for cross-service dashboards. 3) Long-term storage: Local Prometheus retains 15 days. Global Prometheus scrapes aggregated metrics for longer retention.

---

## SECTION 7: Architecture and System Design (Q86–100)

**Q86.** What are the three pillars of observability?
> **Answer:** 1) **Metrics**: Numerical aggregates over time. Answers "how much" and "how often". Fast to query, cheap to store, can't explain WHY. Example: HTTP error rate, JVM heap, request latency percentiles. 2) **Logs**: Discrete events with rich context. Answers "what happened" in detail. Expensive to store and query at scale. Example: individual request logs with stack traces. 3) **Traces**: Request journeys across services. Answers "what path did this request take" and "where is the bottleneck". High cardinality data.

**Q87.** What is the difference between observability and monitoring?
> **Answer:** **Monitoring**: Watching known metrics and alerting when they cross thresholds. Reactive to known failure modes. "CPU > 80% → alert." You KNOW what you're looking for. **Observability**: The ability to understand a system's internal state from external outputs (metrics, logs, traces). Proactive. Allows investigating UNKNOWN failure modes. "Something is wrong — let me investigate WHY." Monitoring is a SUBSET of observability. Highly observable systems allow any question to be answered by querying the data.

**Q88.** What is an SLI, SLO, and SLA?
> **Answer:** **SLI** (Service Level Indicator): A measurable metric that indicates service quality. Example: p95 latency. **SLO** (Service Level Objective): An internal target for an SLI. Example: "p95 latency < 500ms 99% of the time". Set by engineering. **SLA** (Service Level Agreement): A contractual commitment to the customer. Example: "99.9% availability, or we pay penalties." SLOs are more strict than SLAs (buffer for alerts before SLA is breached). For RBI compliance: response time SLA for banking APIs.

**Q89.** What is the purpose of a traceId in the database record (PricingRequest.traceId)?
> **Answer:** When a customer reports an issue: "My pricing request at 10:30 AM yesterday was wrong." Support team: 1) Searches the database for the customer's pricing request. 2) Finds the traceId. 3) Searches Grafana Tempo: "Show trace for traceId=abc123." 4) Sees the complete request journey: what parameters were used, which calculation path was taken, if any errors occurred, exactly how long each step took. This "database → trace" link makes debugging production issues possible without reproducing them.

**Q90.** What is the purpose of the GlobalExceptionHandler in this observability architecture?
> **Answer:** GlobalExceptionHandler is the centralized error observability hub. Every exception (business or technical) flows through it and triggers: 1) Metric increment (`pricing_errors_total{error_type="..."}`) → Grafana can show error rates by type. 2) Structured log (`log.warn/error(...)`) → Grafana Loki can filter errors by type, customer, endpoint. 3) OTel span status set to ERROR → Grafana Tempo shows the span in RED. All three signals share the traceId → they're correlated in Grafana. Without it, error observability would be scattered across every controller and service.

**Q91.** What is the separation of concerns between PricingCalculator, PricingService, and PricingController?
> **Answer:** **PricingCalculator**: Pure math. No side effects. No dependencies. Input → Output. Independently unit-testable. **PricingService**: Orchestrator. Coordinates: calculation, persistence, metrics, logs, traces, audit. Has dependencies. Integration-testable. **PricingController**: Thin API layer. HTTP semantics (status codes, headers). Delegates to Service immediately. E2E-testable. This separation enables: a) Testing PricingCalculator with no mocks (pure function). b) Testing PricingService with mocked repository. c) Easy replacement of any layer without affecting others.

**Q92.** What is the Twelve-Factor App methodology? Which factors does this project address?
> **Answer:** 12-factor is a methodology for building scalable, maintainable SaaS applications. Key factors in this project: 1) Codebase: One codebase, tracked in git. 2) Dependencies: Declared in pom.xml, isolated via Docker. 3) Config: Stored in environment variables (not code). 4) Backing services: Database as attached resource (connection string via env var). 5) Build/release/run: Docker build creates immutable image. 6) Processes: Stateless Spring Boot app. 7) Port binding: Exports services via port 8080. 8) Logs: Treated as streams (JSON to stdout → Promtail). 9) Admin processes: Actuator for admin tasks.

**Q93.** What is circuit breaker pattern? How does it relate to observability?
> **Answer:** Circuit Breaker prevents cascading failures. If a downstream service fails repeatedly, the circuit "opens" and future calls fail fast (without waiting for timeout). States: CLOSED (normal), OPEN (fails fast), HALF-OPEN (tests if service recovered). Observability connection: Each state transition should: 1) Increment a metric (`circuit_breaker.state_transitions_total{service="risk-engine",state="OPEN"}`). 2) Log a structured event. 3) Set an alert (circuit breaker OPEN for >5 minutes → page on-call). This project is ready to add Resilience4j circuit breakers — the metrics infrastructure is already in place.

**Q94.** What is the difference between horizontal and vertical scaling? When does each apply?
> **Answer:** Vertical scaling: Increase the size of ONE instance (more CPU, more RAM). Simple, no code changes required. Limited by hardware maximum. Horizontal scaling: Add more instances (run 5 copies of the service behind a load balancer). Requires: stateless design (no in-memory session), distributed cache (for shared state), service discovery. For our ePricing service: stateless (no session state) → horizontally scalable. In Kubernetes: `kubectl scale deployment epricing-service --replicas=10`. Observability: All replicas' metrics should aggregate in Prometheus (all scraped via service discovery, not static configs).

**Q95.** What is a sidecar pattern? How does it apply to this observability stack?
> **Answer:** Sidecar pattern: A helper container runs alongside the main application container in the same Pod (Kubernetes) or same Docker Compose service. The sidecar provides cross-cutting functionality without modifying the app. In this project: Promtail is effectively a sidecar — it reads the logs that epricing-service writes, processes them, and ships to Loki. The epricing-service doesn't need to know about Loki. In Kubernetes: Promtail as a sidecar reads from the shared emptyDir volume. The OTel Collector can also run as a sidecar for direct trace collection.

**Q96.** What is idempotency? Why is it important in a pricing API?
> **Answer:** An idempotent operation produces the same result regardless of how many times it's executed. POST /pricing is NOT idempotent by default — calling it twice creates two pricing records. In banking: if a client's HTTP request times out and they retry, you don't want to create duplicate pricing records. Solutions: 1) Idempotency key in header (X-Idempotency-Key): server stores the result for the key and returns the cached result for retries. 2) Client-generated requestId stored in PricingRequest: check if requestId already exists before creating. Observability: Monitor `pricing_requests_duplicated_total` counter.

**Q97.** What is the difference between Liveness and Readiness probes in Kubernetes?
> **Answer:** Liveness probe: "Is the application alive?" If it fails: Kubernetes RESTARTS the container. Use for: deadlock detection, memory leak detection. Our `/api/v1/health` or `/actuator/health`. Readiness probe: "Is the application ready to receive traffic?" If it fails: Kubernetes removes the pod from the service's endpoint list (traffic stops flowing to it). The pod is NOT restarted. Use for: startup completion, temporary inability to serve (circuit breaker open, DB connection pool exhausted). Spring Boot Actuator provides `/actuator/health/readiness` and `/actuator/health/liveness` automatically with `management.health.probes.enabled: true`.

**Q98.** How would you implement distributed rate limiting with observability?
> **Answer:** Components: 1) Redis cluster for distributed counter storage. 2) Rate limiter implementation (Bucket4j + Redis). 3) Servlet filter to intercept requests. 4) Metrics: `rate_limit.requests_total{result="allowed"}` and `rate_limit.requests_total{result="throttled"}`. 5) Alert: If throttle rate > 10% → customers are being rate limited. 6) Log: Structured log when throttled: `{event:"RATE_LIMITED", customerId, ip, limit, currentCount}`. 7) Trace: Add span attribute `rate_limit.result=throttled`. This project's observability infrastructure (MeterRegistry, MDCFilter, StructuredLogger) is ready to support this with minimal additions.

**Q99.** What is a blue-green deployment? How does observability enable safer deployments?
> **Answer:** Blue-green: Two identical production environments (blue=current, green=new). Deploy new version to green. Test green. Switch load balancer to send traffic to green. Blue becomes the rollback target. Observability enables: 1) Deploy to green with 5% traffic. 2) Compare Grafana dashboards: green's error rate, latency vs blue's. 3) If green metrics match blue: route 100% traffic. 4) If green shows degradation: rollback by switching load balancer back to blue. Without observability: you're deploying blind — "does the new version work?" is answered by customer complaints, not metrics.

**Q100.** If you had to reduce this project's observability stack for a resource-constrained environment, what would you keep and what would you remove? Justify your choices.
> **Answer (Architect-level):** Must Keep: 1) Spring Boot Actuator + Micrometer + Prometheus (cheapest, highest value). Even one metric — HTTP error rate + latency — tells you if the system is healthy. 2) Structured JSON logging to stdout (zero infrastructure cost — just `docker logs`). 3) Health check endpoint (critical for load balancer/Kubernetes liveness). Remove First: 1) OTel Collector (add later when needed — use simple OTLP direct to Jaeger). 2) Loki + Promtail (use CloudWatch Logs or just `docker logs` initially). 3) Grafana (use Prometheus's built-in UI initially). 4) Custom business metrics (keep only Micrometer auto-metrics). Reasoning: Prometheus scraping + health check + JSON logs covers the "is the system healthy?" question with minimal infrastructure. Add tracing and log aggregation when debugging distributed issues becomes necessary (complexity vs. value trade-off).

---

*End of 100 Interview Questions*

---

## QUICK REFERENCE: Bank ePricing Observability Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                  Bank ePricing Service                      │
│  Spring Boot 3.x │ Java 21 │ H2 Database │ Port 8080           │
│                                                                   │
│  ┌───────────┐  ┌──────────────┐  ┌─────────────────────┐      │
│  │Controllers│→ │PricingService│→ │PricingRepository    │      │
│  └───────────┘  └──────┬───────┘  └─────────────────────┘      │
│                        │                                          │
│              ┌─────────┼─────────────┐                          │
│              ↓         ↓             ↓                          │
│          Metrics     Logs         Traces                         │
│       (Micrometer) (Logback)   (OpenTelemetry)                  │
└──────────┬─────────────┬──────────────┬──────────────────────────┘
           │             │              │
           ↓             ↓              ↓
    /actuator      /app/logs/     OTLP HTTP :4318
    /prometheus    *.log              │
           │             │         OTel Collector
           │         Promtail         │
           │             │        ┌───┴────────┐
           ↓             ↓        ↓            ↓
       Prometheus      Loki   Prometheus   Grafana Tempo
       (storage)    (storage) (via OTel)   (traces)
           │             │              
           └─────────────┴────────────┐
                                      ↓
                               Grafana 3000
                         (Dashboards, Alerts, Explore)
```
