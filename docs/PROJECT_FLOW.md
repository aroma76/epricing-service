# ePricing Observability — Project Flow Guide
## How a Single Pricing Request Flows Through the Complete Stack

---

## THE REQUEST LIFECYCLE — From Customer to Grafana

### PHASE 1: HTTP Request Arrives

```
Client (Postman / Front-end)
    POST /api/v1/pricing
    Headers: X-Customer-ID: CUST001234
    Body: { productType: "HOME_LOAN", loanAmount: 5000000, ... }
```

**What happens first:**

1. **Tomcat** receives the TCP connection on port 8080
2. **MDCFilter** (Servlet Filter, Order=1) intercepts BEFORE any controller:
   - Generates or extracts `X-Request-ID` header → `requestId = "REQ-ABC123"`
   - Reads `X-Customer-ID` header → `customerId = "CUST001234"`
   - Populates MDC: `{requestId, customerId, httpMethod="POST", requestUri="/api/v1/pricing"}`
   - Logs: `INFO Incoming request | method=POST | uri=/api/v1/pricing`
3. **OpenTelemetry Spring MVC Instrumentation** (auto-configured) creates the ROOT SPAN:
   - Generates `traceId = "4bf92f3577b34da6"` (16 bytes, 32 hex chars)
   - Creates `spanId = "00f067aa0ba902b7"` (8 bytes, 16 hex chars)
   - Adds `traceId` and `spanId` to MDC automatically (Micrometer Tracing bridge)
   - Now every log on this thread includes both values automatically
4. **PricingController.calculatePricing()** receives the deserialized DTO
5. **@Valid** triggers Bean Validation → if fails → MethodArgumentNotValidException → GlobalExceptionHandler

### PHASE 2: Business Logic

6. **PricingController** delegates immediately to **PricingService.calculatePricing()**
7. **PricingMetrics.recordPricingRequestReceived()** — increments `pricing_requests_total{type="all"}`
8. **PricingMetrics.incrementActiveRequests()** — increments `pricing_requests_active` gauge (was 0, now 1)
9. **PricingMetrics.recordLoanAmount(5000000.0)** — records in distribution summary
10. **PricingMetrics.recordProductTypeRequest("HOME_LOAN")** — increments product counter
11. **Tracer.spanBuilder("calculatePricing")** creates CHILD SPAN under root span
12. **PricingCalculator.validateEligibility()** — validates credit score ≥ 650, FOIR within limits
    - Child span: "validateEligibility"
13. **PricingCalculator.calculateInterestRate()** — `BASE_RATE × 1.0 + (-0.50) = 8.00%`
    - Child span: "computeInterestRate"
    - Timer records calculation duration: ~2ms
14. **PricingCalculator.calculateEmi()** — EMI formula with BigDecimal precision
15. **PricingRepository.save(entity)** — Hibernate INSERT into H2 `pricing_requests` table
    - Child span: "persistPricingRequest"
    - HikariCP: gets connection from pool, executes INSERT, returns connection to pool
16. **StructuredLogger.logPricingCompleted()** → JSON log with all context
17. **PricingAuditService.recordSuccess()** — runs ASYNCHRONOUSLY on `epricing-async-1` thread
    - Separate transaction (REQUIRES_NEW)
    - INSERT into `pricing_audit_logs` table
18. **PricingMetrics.recordPricingSuccess()** — increments `pricing_requests_total{type="success"}`
19. **PricingMetrics.decrementActiveRequests()** — gauge decrements (was 1, now 0)
20. All spans ended in finally blocks (reverse order: child before parent)

### PHASE 3: HTTP Response

21. Controller returns `ResponseEntity.status(201).body(response)`
22. Jackson serializes PricingResponseDto to JSON
23. Response headers include: `X-Request-ID: REQ-ABC123`
24. **MDCFilter** logs: `INFO Request completed | status=201 | durationMs=142`
25. **MDCFilter finally block**: `MDC.clear()` — CRITICAL for thread pool safety
26. Thread returned to Tomcat thread pool (with clean MDC)

---

## PHASE 4: Metrics Pipeline

```
Every 15 seconds:
Prometheus → GET http://epricing-service:8081/actuator/prometheus

Response (excerpt):
# HELP pricing_requests_total
pricing_requests_total{application="epricing-service",team="epricing",type="all"} 251.0
pricing_requests_total{type="success"} 247.0
pricing_requests_total{type="rejected"} 4.0
# HELP pricing_requests_active
pricing_requests_active{service="epricing"} 0.0
# HELP pricing_calculation_duration_seconds
pricing_calculation_duration_seconds_count 247.0
pricing_calculation_duration_seconds_sum 0.497
pricing_calculation_duration_seconds_bucket{le="0.05"} 190.0
pricing_calculation_duration_seconds_bucket{le="0.1"} 240.0
pricing_calculation_duration_seconds_bucket{le="0.2"} 247.0
...

Prometheus stores each value as a time series:
(metric_name, labels) → [(timestamp, value), ...]
```

**In Grafana:**
- Panel: "P95 Latency" runs `histogram_quantile(0.95, rate(pricing_calculation_duration_seconds_bucket[5m])) * 1000`
- Result: 47ms (95% of calculations complete within 47ms)

---

## PHASE 5: Logs Pipeline

```
What's in /app/logs/epricing-service.log (one line, formatted for readability):
{
  "@timestamp": "2024-01-15T10:30:00.142Z",
  "level": "INFO",
  "thread_name": "http-nio-8080-exec-3",
  "logger_name": "com.bank.epricing.service.PricingService",
  "message": "Pricing calculation completed | event_type=PRICING_COMPLETED | customerId=CUST001234 | productType=HOME_LOAN | rate=8.00% | emi=41822.18 | durationMs=142",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId": "00f067aa0ba902b7",
  "customerId": "CUST001234",
  "requestId": "REQ-ABC123",
  "application": "epricing-service",
  "environment": "docker"
}

Promtail reads this line:
  → Stage 1 (json): Parses JSON, extracts fields
  → Stage 2 (labels): Sets Loki labels: {level="INFO", application="epricing-service", traceId="4bf92f3577b34da6a3ce929d0e0e4736"}
  → Stage 3 (timestamp): Uses @timestamp as Loki entry time
  → Stage 4 (output): Uses "message" field as log line content
  → Ships to Loki: POST http://loki:3100/loki/api/v1/push
```

**In Grafana:**
- Logs panel query: `{application="epricing-service"} | json | event_type="PRICING_COMPLETED"`
- To find all logs for one request: `{application="epricing-service"} | json | traceId = "4bf92f3577b34da6a3ce929d0e0e4736"`

---

## PHASE 6: Traces Pipeline

```
OTel SDK in Spring Boot collects spans in memory:

Trace: 4bf92f3577b34da6a3ce929d0e0e4736
├── Span: POST /api/v1/pricing (0ms - 142ms) ← auto-created
│   Attributes: http.method=POST, http.url=/api/v1/pricing, http.status_code=201
│   ├── Span: calculatePricing (2ms - 140ms) ← PricingService
│   │   Attributes: customer.id=CUST001234, product.type=HOME_LOAN, loan.amount=5000000
│   │   ├── Span: validateEligibility (2ms - 7ms)
│   │   │   Attributes: credit.score=780
│   │   │   Events: "Eligibility validation passed"
│   │   ├── Span: computeInterestRate (7ms - 17ms)
│   │   │   Attributes: calculated.rate=8.00
│   │   │   Events: "Interest rate computed successfully"
│   │   └── Span: persistPricingRequest (17ms - 140ms)  ← DB is bottleneck!
│   │       Attributes: db.operation=INSERT, db.table=pricing_requests, db.row.id=42
│   │       Events: "Pricing request persisted to database"

OTel BatchSpanProcessor queues spans
OTel OTLP HTTP Exporter: POST http://otel-collector:4318/v1/traces
OTel Collector: memory_limiter → batch → debug exporter (prints to collector logs)
```

**In Grafana Tempo** (when Tempo is added to docker-compose):
- Search by traceId: `4bf92f3577b34da6a3ce929d0e0e4736`
- See waterfall showing: DB took 123ms of the 142ms total → optimization target

---

## ERROR SCENARIO: Low Credit Score

**What happens differently:**

1. Steps 1-12 same as above
2. **PricingCalculator.validateEligibility()** detects creditScore=620 < 650 (minimum)
3. Throws: `InsufficientCreditScoreException`
4. In PricingService catch block:
   - `pricingMetrics.recordPricingRejection()` → counter `pricing_requests_total{type="rejected"}` increments
   - `structuredLogger.logPricingRejected(...)` → WARN level log
   - `auditService.recordRejection(...)` → async audit INSERT
   - `validationSpan.setStatus(ERROR, "Credit score too low")`
   - Exception rethrown
5. **GlobalExceptionHandler** catches the exception:
   - `meterRegistry.counter("pricing.errors.total", "error_type", "PRICING_INSUFFICIENT_CREDIT_SCORE").increment()`
   - `log.warn("Business exception handled | errorCode=...")` — WARN not ERROR (business rule, not bug)
   - `Span.current().setStatus(ERROR, message)` + `recordException(ex)`
6. **HTTP Response 422** (Unprocessable Entity):
   ```json
   {
     "status": 422,
     "error_code": "PRICING_INSUFFICIENT_CREDIT_SCORE",
     "message": "Customer CUST001234 has credit score 620 which is below minimum 650",
     "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736"
   }
   ```
7. **In Grafana:**
   - Metrics: `pricing_requests_total{type="rejected"}` counter increases
   - Logs: Search `{application="epricing-service"} | json | event_type = "PRICING_REJECTED"`
   - Traces: The span is marked RED (ERROR status)

---

## COMPLETE TECHNOLOGY INTERACTION MAP

```
                         pom.xml (dependencies)
                              │
                    application.yml (config)
                              │
              ┌───────────────┼───────────────────┐
              │               │                   │
        EpricingService    MDCFilter          OpenTelemetry
        Application.java   (logging ctx)     Config (tracing)
              │               │                   │
              ↓               ↓                   ↓
        [HTTP Layer]     [MDC Thread-Local]   [OTel Context]
        PricingController                          │
              │                              traceId in MDC
              ↓                              (Micrometer bridge)
        PricingService ─────────────────→ PricingMetrics
              │              orchestrates    (Micrometer API)
              ├──────────────────────────→ StructuredLogger
              │                            (SLF4J/Logback)
              ├──────────────────────────→ PricingCalculator
              │                            (pure math)
              ├──────────────────────────→ PricingRepository
              │                            (JPA/Hibernate/H2)
              └──────────────────────────→ PricingAuditService
                                           (async audit)
              │
              ↓
    [On Error: GlobalExceptionHandler]
              │
              ↓
    ┌─────────────────────────────────┐
    │         OUTPUT SIGNALS          │
    ├─────────────────────────────────┤
    │ METRICS (Micrometer)           │ → /actuator/prometheus (port 8081)
    │ LOGS (Logback JSON)            │ → /app/logs/epricing-service.log
    │ TRACES (OTel OTLP)             │ → otel-collector:4318
    └─────────────────────────────────┘
              │
    ┌─────────┼──────────────────────┐
    ↓         ↓                      ↓
Prometheus  Promtail→Loki      OTel Collector
(scrapes)   (reads,ships)      (processes)
    │              │                 │
    └──────────────┴─────────────────┘
                   │
                   ↓
            Grafana :3000
     ┌─────────────────────────┐
     │  Dashboards             │
     │  ├─ Metrics (Prometheus)│
     │  ├─ Logs (Loki)         │
     │  └─ Traces (Tempo)      │
     │  Alerts (Prometheus)    │
     │  Explore (Ad-hoc query) │
     └─────────────────────────┘
```
