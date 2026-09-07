# OpenTelemetry Configuration and Distributed Tracing

**Package:** `com.bank.epricing.config`
**File:** [`OpenTelemetryConfig.java`](../../src/main/java/com/bank/epricing/config/OpenTelemetryConfig.java)

---

## Purpose

`OpenTelemetryConfig` initializes the **OpenTelemetry SDK** and exposes a Spring-managed `Tracer` bean. This bridges OTel's SDK initialization with Spring Boot's dependency injection system, allowing `PricingService` to create custom child spans via constructor injection.

---

## Configuration Beans

### `openTelemetry() → OpenTelemetry`

Uses `AutoConfiguredOpenTelemetrySdk.builder()` to initialize the full OTel SDK from configuration:

```java
@Bean
public OpenTelemetry openTelemetry() {
    return AutoConfiguredOpenTelemetrySdk.builder()
        .addPropertiesSupplier(() -> {
            Map<String, String> props = new HashMap<>();
            props.put("otel.service.name", serviceName);
            props.put("otel.exporter.otlp.endpoint", otlpEndpoint);
            props.put("otel.exporter.otlp.protocol", "http/protobuf");
            props.put("otel.traces.sampler", "parentbased_always_on");
            props.put("otel.resource.attributes",
                "service.namespace=Bank,team.name=epricing,deployment.environment=local");
            return props;
        })
        .build()
        .getOpenTelemetrySdk();
}
```

Key configuration:
- **`otel.exporter.otlp.protocol: http/protobuf`** — Uses HTTP port 4318 (not gRPC 4317).
- **`parentbased_always_on`** sampler — Traces 100% of requests in dev. In production, set to `parentbased_traceidratio` with `0.1` (10%) to reduce overhead.

### `tracer(OpenTelemetry) → Tracer`

```java
@Bean
public Tracer tracer(OpenTelemetry openTelemetry) {
    return openTelemetry.getTracer("com.bank.epricing", "1.0.0");
}
```

The `Tracer` is injected into `PricingService` for creating custom child spans. The instrumentation library name (`com.bank.epricing`) and version (`1.0.0`) appear in Grafana Tempo as the span's "scope."

---

## OTel Configuration Properties

Defined in `application.yml` and overridable via environment variables (OTel specification):

| Property | Value | Env Var Override |
|---|---|---|
| `otel.service.name` | `epricing-service` | `OTEL_SERVICE_NAME` |
| `otel.exporter.otlp.endpoint` | `http://otel-collector:4318` | `OTEL_EXPORTER_OTLP_ENDPOINT` |
| `otel.exporter.otlp.protocol` | `http/protobuf` | `OTEL_EXPORTER_OTLP_PROTOCOL` |
| `otel.resource.attributes` | `service.namespace=Bank,...` | `OTEL_RESOURCE_ATTRIBUTES` |
| `management.tracing.sampling.probability` | `1.0` (dev) / `0.1` (prod) | — |

---

## Span Anatomy

### Automatic Spans (Spring MVC auto-instrumentation)

For every HTTP request, `opentelemetry-spring-webmvc-6.0` automatically creates a root span:

```
POST /api/v1/pricing
  ├── span: HTTP POST /api/v1/pricing
  │     attributes: http.method=POST, http.url=/api/v1/pricing,
  │                 http.status_code=201, net.peer.ip=...
  │
  └── [child spans ...]
```

### Custom Child Spans (PricingService)

`PricingService` creates a custom child span for the business operation:

```java
Span span = tracer.spanBuilder("pricing-calculation")
    .setAttribute("customer.id", dto.getCustomerId())
    .setAttribute("product.type", dto.getProductType())
    .setAttribute("loan.amount", dto.getLoanAmount().longValue())
    .setAttribute("credit.score", dto.getCreditScore())
    .startSpan();

try (Scope scope = span.makeCurrent()) {
    // business logic runs here
    span.setStatus(StatusCode.OK);
} catch (Exception e) {
    span.setStatus(StatusCode.ERROR, e.getMessage());
    span.recordException(e);
    throw e;
} finally {
    span.end();
}
```

**Full trace tree in Grafana Tempo:**
```
POST /api/v1/pricing  [201]  [142ms]
  └── pricing-calculation  [89ms]
        attributes:
          customer.id: CUST001234
          product.type: HOME_LOAN
          loan.amount: 5000000
          credit.score: 780
        ├── SELECT pricing_requests  [12ms]   (Hibernate auto-span)
        └── INSERT pricing_requests  [18ms]   (Hibernate auto-span)
```

---

## Context Propagation

OTel uses **W3C TraceContext** headers (`traceparent`, `tracestate`) for distributed trace propagation. When an upstream service makes a request to epricing-service with a `traceparent` header, the new spans are created as children of that upstream trace — giving end-to-end visibility across service boundaries.

---

## Sampling

```yaml
management:
  tracing:
    sampling:
      probability: 1.0  # 100% in dev
```

**Production recommendation:** Use `0.1` (10%) or lower to reduce:
- Network overhead (OTLP export volume)
- Grafana Tempo storage costs
- Performance impact (span serialization)

For high-value transactions (e.g., large loan amounts), use a custom sampler to always trace 100% regardless of the overall rate.

---

## Micrometer Tracing Bridge

The dependency `micrometer-tracing-bridge-otel` bridges Spring Boot's auto-instrumentation with the OTel SDK. Without it:
- Spring Boot's HTTP instrumentation would not produce OTel traces
- `traceId` / `spanId` would not be automatically added to MDC (and thus to log entries)

With the bridge, Micrometer Tracing and OTel SDK share the same trace context — one unified trace per request.

---

## Cross-References

- [PricingService.md](../services/PricingService.md) — creates custom spans
- [LoggingStrategy.md](../logging/LoggingStrategy.md) — traceId in log MDC
- [DockerCompose.md](../docker/DockerCompose.md) — OTel Collector service
- [OpenTelemetry.md](./OpenTelemetry.md) — full OTel pipeline doc
