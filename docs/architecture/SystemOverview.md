# System Architecture Overview

## Purpose

This document describes the high-level architecture of the Bank ePricing Service — its layers, components, data flows, and the complete observability stack.

---

## System Context

The ePricing Service is a standalone REST microservice that sits within Bank's loan-origination ecosystem. External clients (browser-based dashboards, mobile apps, or upstream services) submit loan pricing requests over HTTP. The service computes interest rates, EMI amounts, and risk categories, persists results, and emits full telemetry (metrics, logs, traces) to the monitoring stack.

```mermaid
graph LR
    ExternalClient["External Client\n(Browser / Mobile / Upstream Service)"]
    APIGateway["API Gateway / Load Balancer\n(Not in scope)"]
    App["epricing-service\n(Spring Boot 3.3.4)"]
    DB["H2 In-Memory DB\n(Prod: PostgreSQL / Oracle)"]

    ExternalClient -->|HTTP| APIGateway -->|HTTP :8080| App
    App -->|JPA/JDBC| DB
```

---

## Application Layer Architecture

The service uses a strict, unidirectional layered architecture. Dependencies only flow downward.

```mermaid
graph TD
    subgraph HTTP Layer
        Filter["MDCFilter\n@Order(1)"]
        PC["PricingController\n@RestController"]
        HC["HealthController\n@RestController"]
    end

    subgraph Service Layer
        PS["PricingService\n@Service @Transactional"]
        PAS["PricingAuditService\n@Service @Async"]
    end

    subgraph Utility Layer
        Calc["PricingCalculator\n@Component — Pure Math"]
    end

    subgraph Data Layer
        PR["PricingRepository\n@Repository — JPA"]
        PAR["PricingAuditRepository\n@Repository — JPA"]
    end

    subgraph Observability
        PM["PricingMetrics\n@Component — Micrometer"]
        SL["StructuredLogger\n@Component — SLF4J+MDC"]
        OTel["OpenTelemetry Tracer\n@Bean — Spans"]
    end

    Filter --> PC
    PC --> PS
    PS --> Calc
    PS --> PAS
    PS --> PR
    PAS --> PAR
    PS --> PM
    PS --> SL
    PS --> OTel
```

**Layer responsibilities:**

| Layer | Responsibility |
|---|---|
| **HTTP Layer** | HTTP routing, request/response mapping, MDC population, validation |
| **Service Layer** | Business orchestration, transaction management, async audit dispatch |
| **Utility Layer** | Pure, side-effect-free mathematical calculations |
| **Data Layer** | Persistence — CRUD and domain-specific queries |
| **Observability** | Cross-cutting: metrics recording, structured logging, distributed tracing |

---

## Component Catalog

| Component | Package | Role |
|---|---|---|
| `EpricingServiceApplication` | root | Spring Boot entry point |
| `PricingController` | controller | REST endpoint `/api/v1/pricing` |
| `HealthController` | controller | Custom health/metrics endpoints |
| `PricingService` | service | Core business orchestrator |
| `PricingAuditService` | service | Async, transactionally-isolated audit writer |
| `PricingCalculator` | util | Interest rate, EMI, eligibility logic |
| `PricingMetrics` | metrics | Custom Micrometer metric registration |
| `StructuredLogger` | logging | Typed, structured log event methods |
| `MDCFilter` | logging | Servlet filter populating thread-local log context |
| `GlobalExceptionHandler` | exception | `@RestControllerAdvice` — maps exceptions to HTTP responses |
| `PricingRepository` | repository | JPA repository for `PricingRequest` entity |
| `PricingAuditRepository` | repository | JPA repository for `PricingAuditLog` entity |
| `WebConfig` | config | CORS rules and async thread pool |
| `OpenTelemetryConfig` | config | OTel SDK initialization and `Tracer` bean |

---

## Request Lifecycle

```mermaid
sequenceDiagram
    participant Client
    participant MDCFilter
    participant PricingController
    participant PricingService
    participant PricingCalculator
    participant PricingRepository
    participant PricingAuditService

    Client->>MDCFilter: HTTP POST /api/v1/pricing
    MDCFilter->>MDCFilter: Set requestId, clientIp in MDC
    MDCFilter->>PricingController: Forward request
    PricingController->>PricingController: Validate DTO (@Valid)
    PricingController->>PricingService: calculatePricing(dto, ip)
    PricingService->>PricingService: Start OTel span, record metrics
    PricingService->>PricingCalculator: validateEligibility()
    PricingCalculator-->>PricingService: Pass / throw exception
    PricingService->>PricingCalculator: calculateInterestRate()
    PricingCalculator-->>PricingService: BigDecimal rate
    PricingService->>PricingCalculator: calculateEmi()
    PricingCalculator-->>PricingService: BigDecimal emi
    PricingService->>PricingRepository: save(pendingRequest)
    PricingService->>PricingRepository: save(calculatedRequest)
    PricingService->>PricingAuditService: logAsync(auditEntry)
    PricingAuditService-->>PricingAuditService: Async — own TX
    PricingService-->>PricingController: PricingResponseDto
    PricingController-->>MDCFilter: 201 Created + response body
    MDCFilter->>MDCFilter: MDC.clear()
    MDCFilter-->>Client: HTTP 201 + X-Request-ID header
```

---

## Observability Architecture

The observability strategy is built on three pillars — the **Grafana LGTM stack**.

```mermaid
graph LR
    subgraph epricing-service Container
        App["Spring Boot App"]
        Logfile["JSON Log File\n/app/logs/*.log"]
        App -->|writes JSON logs| Logfile
        App -->|push OTLP traces+metrics| OTelCol
        App -->|expose /actuator/prometheus| Prometheus
    end

    subgraph Telemetry Pipeline
        OTelCol["OTel Collector :4318\notel-collector-config.yml"]
    end

    subgraph Storage
        Prometheus["Prometheus :9090\n(metrics TSDB)"]
        Loki["Loki :3100\n(log store)"]
        Tempo["Grafana Tempo\n(trace store — optional)"]
    end

    subgraph Agents
        Promtail["Promtail\n(reads Logfile → ships to Loki)"]
    end

    subgraph Visualization
        Grafana["Grafana :3000\n(dashboards + explore)"]
    end

    Logfile -->|tailed by| Promtail --> Loki
    OTelCol -->|prometheus exporter :8889| Prometheus
    OTelCol --> Tempo
    Prometheus --> Grafana
    Loki --> Grafana
    Tempo --> Grafana
```

### Correlation Strategy

All three signals are correlated by a shared **`traceId`**:

1. **MDCFilter** generates a `requestId` and populates MDC.
2. **Micrometer Tracing** automatically puts `traceId` and `spanId` into MDC.
3. **Logback/LogstashEncoder** includes all MDC keys in every JSON log line → Loki indexes `traceId`.
4. **OTel spans** carry the same `traceId` → Grafana Tempo stores them.
5. From any Grafana panel, you can pivot: metric spike → click trace → see all logs with that traceId.

---

## Database Design

Two JPA entities backed by two H2 tables:

| Table | Entity | Purpose |
|---|---|---|
| `pricing_requests` | `PricingRequest` | Primary pricing result and status |
| `pricing_audit_logs` | `PricingAuditLog` | Immutable append-only audit trail |

See [DatabaseDesign.md](../database/DatabaseDesign.md) for schema and ERD.

---

## Security Notes

> **Note:** Authentication and authorization are **not implemented** in this service. In a production deployment the following would be required:
> - JWT token validation (via Spring Security + OAuth2 Resource Server)
> - Role-based access control for admin endpoints
> - TLS on all inter-service communication
> - Actuator endpoints locked behind the management network (port 8081 not public-facing)
> - Secret management via HashiCorp Vault or AWS Secrets Manager

---

## Cross-References

- [PricingService.md](../services/PricingService.md)
- [PricingController.md](../controllers/PricingController.md)
- [OpenTelemetry.md](../monitoring/OpenTelemetry.md)
- [LoggingStrategy.md](../logging/LoggingStrategy.md)
- [DockerCompose.md](../docker/DockerCompose.md)
