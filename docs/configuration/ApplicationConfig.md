# Application Configuration Reference

**File:** [`application.yml`](../../src/main/resources/application.yml)

---

## Overview

`application.yml` is the **single central configuration file** for the ePricing service. It drives all runtime behaviour: server ports, database connectivity, actuator endpoints, metrics configuration, logging, OpenTelemetry, and business rules.

---

## Spring Core

```yaml
spring:
  application:
    name: epricing-service        # Used in Prometheus labels, OTel service name, logs
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:local}   # Override via env var
```

The `spring.application.name` propagates through:
- Prometheus label: `application="epricing-service"`
- OTel `service.name` resource attribute
- Grafana dashboard titles
- Every JSON log line (via Logback custom fields)

---

## Database (YugabyteDB Distributed SQL — YSQL)

```yaml
spring:
  datasource:
    url: jdbc:yugabytedb://${DB_HOST:localhost}:${DB_PORT:5433}/${DB_NAME:epricingdb}?load-balance=true&sslmode=disable
    driver-class-name: com.yugabyte.Driver
    username: ${DB_USERNAME:yugabyte}
    password: ${DB_PASSWORD:yugabyte}
    hikari:
      pool-name: epricing-pool
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000   # ms — wait for pool connection
      idle-timeout: 600000        # ms — idle connection expiry
      max-lifetime: 1800000       # ms — connection max lifetime
      leak-detection-threshold: 60000
```

- `jdbc:yugabytedb://`: Uses YugabyteDB Smart Driver for cluster-aware client-side load balancing.
- `load-balance=true`: Distributes queries evenly across active T-Servers.
- Pool name `epricing-pool` appears in Prometheus metrics: `hikaricp_connections_active{pool="epricing-pool"}`.

---

## Flyway Database Migrations

```yaml
spring:
  flyway:
    enabled: true
    baseline-on-migrate: true
    baseline-version: 0
    locations: classpath:db/migration
    validate-on-migrate: true
```

- Flyway manages immutable, versioned schema definitions in `src/main/resources/db/migration/`.

---

## JPA / Hibernate

```yaml
spring:
  jpa:
    database-platform: org.hibernate.dialect.PostgreSQLDialect
    hibernate:
      ddl-auto: validate                   # Enforce entity validation against Flyway schema
    show-sql: false                        # Never in production (security + performance)
    properties:
      hibernate:
        generate_statistics: true          # Enables JPA metrics in Grafana
        jdbc:
          batch_size: 50                   # Batch DB operations
          order_inserts: true              # Sort for batch efficiency
```

- `generate_statistics: true`: Required for Hibernate statistics metrics (entity fetch counts, query counts) to appear in `/actuator/prometheus`.
- `ddl-auto: validate`: Ensures Hibernate does not alter tables directly, preserving Flyway's role as the single source of truth.

---

## H2 Console (Development Only)

```yaml
spring:
  h2:
    console:
      enabled: true
      path: /h2-console     # http://localhost:8080/api/v1/h2-console
```

Login credentials: `epricing_user` / `epricing_secret` with JDBC URL `jdbc:h2:mem:epricingdb`.

> **Never enable in production.** The H2 console is a SQL interface with no default authentication.

---

## Server Configuration

```yaml
server:
  port: 8080
  servlet:
    context-path: /api/v1             # All endpoints prefixed with /api/v1
  error:
    include-message: always
    include-binding-errors: always
    include-stacktrace: never         # Security: never expose stack traces
```

---

## Management (Actuator)

```yaml
management:
  server:
    port: 8081                        # Separate management port — not public-facing

  endpoints:
    web:
      base-path: /actuator
      exposure:
        include: health,info,prometheus,metrics,loggers,env,threaddump,beans,conditions,configprops

  endpoint:
    health:
      show-details: always
      group:
        liveness:
          include: ping               # K8s liveness probe
        readiness:
          include: db, diskSpace      # K8s readiness probe
```

**Exposed Actuator Endpoints:**

| Endpoint | URL | Purpose |
|---|---|---|
| `health` | `:8081/actuator/health` | Application health |
| `health/liveness` | `:8081/actuator/health/liveness` | Kubernetes liveness probe |
| `health/readiness` | `:8081/actuator/health/readiness` | Kubernetes readiness probe |
| `prometheus` | `:8081/actuator/prometheus` | Prometheus scrape target |
| `metrics` | `:8081/actuator/metrics` | Human-readable metrics |
| `loggers` | `:8081/actuator/loggers` | Runtime log level changes |
| `env` | `:8081/actuator/env` | Configuration properties |
| `threaddump` | `:8081/actuator/threaddump` | JVM thread state |
| `beans` | `:8081/actuator/beans` | Spring bean catalog |

---

## Metrics Configuration

```yaml
management:
  metrics:
    tags:
      application: ${spring.application.name}
      team: epricing
      region: india
      environment: ${spring.profiles.active}
    distribution:
      percentiles-histogram:
        http.server.requests: true
        pricing.calculation.duration: true
      percentiles:
        http.server.requests: 0.5, 0.75, 0.90, 0.95, 0.99
        pricing.calculation.duration: 0.5, 0.90, 0.95, 0.99
      sla:
        http.server.requests: 50ms, 100ms, 200ms, 500ms, 1s, 2s
```

- **Common tags**: Added to ALL metrics — enables cross-instance aggregation in Grafana.
- **Percentile histograms**: Required for `histogram_quantile(0.95, ...)` Grafana queries.
- **SLA buckets**: Count requests completing within 50ms, 100ms, 200ms, 500ms, 1s, 2s.

---

## Tracing Configuration

```yaml
management:
  tracing:
    sampling:
      probability: 1.0    # Trace 100% in dev; use 0.1 in production
  otlp:
    metrics:
      export:
        url: http://otel-collector:4318/v1/metrics
        step: 15s          # Push metrics every 15s
    tracing:
      export:
        url: http://otel-collector:4318/v1/traces
```

---

## Logging

```yaml
logging:
  file:
    path: ./logs           # Promtail reads *.log files from this directory
  level:
    root: INFO
    com.bank: DEBUG        # Verbose for all Bank code
    org.springframework.web: INFO
    org.hibernate: WARN
```

---

## Business Configuration

```yaml
epricing:
  pricing:
    base-rate: 8.5                    # Base interest rate before adjustments
    risk-multiplier:
      home-loan: 1.0
      personal-loan: 1.8
      business-loan: 1.5
      auto-loan: 1.2
    max-deviation-percent: 15
  rate-limit:
    requests-per-second: 100
    burst-capacity: 200
  async:
    core-pool-size: 5
    max-pool-size: 20
    queue-capacity: 100
    thread-name-prefix: epricing-async-
```

> **Note:** The `epricing.*` configuration properties are defined in `application.yml` but the `rate-limit` configuration is **not yet implemented** in application code. Rate limiting is documented as a future feature.

---

## Environment Variable Overrides

Any `application.yml` property can be overridden by environment variables following Spring's relaxed binding rules:

| Property | Environment Variable |
|---|---|
| `spring.profiles.active` | `SPRING_PROFILES_ACTIVE` |
| `otel.exporter.otlp.endpoint` | `OTEL_EXPORTER_OTLP_ENDPOINT` |
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` |
| `management.otlp.metrics.export.url` | `MANAGEMENT_OTLP_METRICS_EXPORT_URL` |
| `logging.file.path` | `LOGGING_FILE_PATH` |

---

## Cross-References

- [WebConfig.md](./WebConfig.md) — async thread pool
- [OpenTelemetryConfig.md](./OpenTelemetryConfig.md) — OTel SDK initialization
- [Prometheus.md](../monitoring/Prometheus.md) — metrics scraping
- [DockerCompose.md](../docker/DockerCompose.md) — environment overrides in Docker
