# Prometheus — Metrics Collection and Alerting

**File:** [`prometheus.yml`](../../prometheus.yml)
**Container Image:** `prom/prometheus:v2.53.0`
**Port:** `9090` (UI) | Accessed via Docker Compose

---

## Purpose

Prometheus is the **time-series database** for all metrics in the ePricing observability stack. It:

1. **Pulls** (scrapes) metrics from targets at regular intervals.
2. **Stores** them in its embedded TSDB (time-series database).
3. **Evaluates** alerting and recording rules continuously.
4. **Serves** metrics data to Grafana via PromQL queries.

---

## Scrape Model (Pull vs Push)

Prometheus uses a **pull model**: it periodically sends `GET` requests to each configured target's metrics endpoint, rather than having applications push metrics to it.

```
Prometheus → GET http://epricing-service:8081/actuator/prometheus
            ← 10,000+ lines of metric text
```

**Advantages:**
- Prometheus controls timing (consistent intervals)
- A missing scrape means the target is down (Prometheus detects outages)
- No configuration changes in the application to add new scrape targets

---

## Global Configuration

```yaml
global:
  scrape_interval: 15s        # Default scrape frequency
  scrape_timeout: 10s         # Timeout per scrape
  evaluation_interval: 15s    # How often to evaluate alert rules

  external_labels:
    monitor: 'epricing-prometheus-local'
    region: 'india'
    environment: 'local'
```

External labels are added to all metrics and alerts — useful when shipping metrics from multiple Prometheus instances to a central aggregator (Thanos/Cortex).

---

## Scrape Jobs

### Job 1: `epricing-service`

```yaml
- job_name: 'epricing-service'
  scrape_interval: 10s          # Faster than default — fresh business metrics
  metrics_path: '/actuator/prometheus'
  scheme: http
  static_configs:
    - targets: ['epricing-service:8081']
      labels:
        application: 'epricing-service'
        team: 'epricing'
        service_type: 'microservice'
```

**URL scraped:** `http://epricing-service:8081/actuator/prometheus`

The management port (`8081`) is separate from the API port (`8080`), ensuring Prometheus traffic does not mix with customer traffic.

---

### Job 2: `prometheus`

Prometheus monitoring itself. Tracks: scrape success/failure, query performance, TSDB storage.

```yaml
- job_name: 'prometheus'
  scrape_interval: 30s
  static_configs:
    - targets: ['localhost:9090']
```

---

### Job 3: `otel-collector`

The OTel Collector exposes its internal metrics (spans received, export failures) on port 8888.

```yaml
- job_name: 'otel-collector'
  scrape_interval: 30s
  static_configs:
    - targets: ['otel-collector:8888']
```

---

### Job 4: `otel-metrics-from-collector`

The OTel Collector re-exposes metrics it received from epricing-service (via OTLP) in Prometheus format on port 8889. This provides **dual redundancy** — metrics reach Prometheus via two paths:

1. Direct scrape: `Prometheus → :8081/actuator/prometheus`
2. Via OTel Collector: `App → OTLP → Collector → :8889 → Prometheus`

---

### Job 5: `cadvisor`

cAdvisor collects container-level resource metrics (CPU, memory, filesystem, network) for all running Docker containers.

```yaml
- job_name: 'cadvisor'
  scrape_interval: 15s
  static_configs:
    - targets: ['cadvisor:8080']
```

**Key metrics:** `container_cpu_usage_seconds_total`, `container_memory_usage_bytes`

---

### Job 6: `node-exporter`

Node Exporter collects host OS metrics (hardware and system-level).

```yaml
- job_name: 'node-exporter'
  scrape_interval: 15s
  static_configs:
    - targets: ['node-exporter:9100']
```

**Key metrics:** `node_cpu_seconds_total`, `node_memory_MemAvailable_bytes`, `node_disk_io_time_seconds_total`

---

## Useful PromQL Queries

### HTTP Error Rate

```promql
(
  rate(http_server_requests_seconds_count{
    job="epricing-service",
    status=~"5.."
  }[5m])
  /
  rate(http_server_requests_seconds_count{
    job="epricing-service"
  }[5m])
) * 100
```

### P95 Request Latency

```promql
histogram_quantile(0.95,
  rate(http_server_requests_seconds_bucket{
    job="epricing-service"
  }[5m])
)
```

### Pricing Success Rate

```promql
(
  rate(pricing_requests_total{type="success"}[5m])
  / rate(pricing_requests_total{type="all"}[5m])
) * 100
```

### Active DB Connections

```promql
hikaricp_connections_active{pool="epricing-pool"}
```

### JVM Heap Usage %

```promql
(
  jvm_memory_used_bytes{area="heap"}
  / jvm_memory_max_bytes{area="heap"}
) * 100
```

---

## Data Retention

```yaml
command:
  - '--storage.tsdb.retention.time=30d'
```

Prometheus retains 30 days of metric history locally. For longer retention, configure `remote_write` to Thanos, Cortex, or Grafana Mimir.

---

## Alert Rules

Alert rules are stored in `prometheus/alerts/application-alerts.yml`. See [AlertingRules.md](./AlertingRules.md) for full documentation.

---

## Cross-References

- [PricingMetrics.md](./PricingMetrics.md) — custom metric definitions
- [Grafana.md](./Grafana.md) — PromQL in dashboards
- [AlertingRules.md](./AlertingRules.md) — Prometheus alert rules
- [DockerCompose.md](../docker/DockerCompose.md) — Prometheus container setup
- [ApplicationConfig.md](../configuration/ApplicationConfig.md) — metrics configuration in Spring Boot
