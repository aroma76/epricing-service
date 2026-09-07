# Grafana — Dashboards and Visualization

**Container Image:** `grafana/grafana:11.1.0`
**Port:** `3000`
**Default Credentials:** `admin` / `Bank@grafana123`

---

## Purpose

Grafana is the **"single pane of glass"** for the ePricing observability stack. It provides:

- **Metrics dashboards** — PromQL queries against Prometheus
- **Log exploration** — LogQL queries against Loki
- **Trace investigation** — trace timeline from Grafana Tempo (optional)
- **Cross-signal correlation** — click a metric spike → jump to logs at that timestamp

---

## Auto-Provisioning

Grafana is fully configured at startup via provisioning files. No manual setup is required.

```
grafana/
├── provisioning/
│   ├── datasources/       # Data source definitions (Prometheus, Loki)
│   └── dashboards/        # Dashboard provider configuration
└── dashboards/
    ├── epricing-complete-dashboard.json        # Main ePricing dashboard
    └── container-nodes-pods-utilization.json  # Container/host metrics dashboard
```

### Datasources (Auto-Provisioned)

Configured in `grafana/provisioning/datasources/`. On startup, Grafana loads:

| Datasource | Type | URL |
|---|---|---|
| Prometheus | Prometheus | `http://prometheus:9090` |
| Loki | Loki | `http://loki:3100` |

### Dashboards (Auto-Provisioned)

Configured in `grafana/provisioning/dashboards/`. Grafana loads all JSON dashboards from `grafana/dashboards/` at startup. Changes to JSON files require a Grafana restart or provisioning reload.

---

## Dashboards

### epricing-complete-dashboard.json

Main operations dashboard with panels for:

| Panel | Metric | PromQL |
|---|---|---|
| Total Pricing Requests | Counter | `rate(pricing_requests_total{type="all"}[5m])` |
| Success Rate | Calculated | `success / all × 100` |
| Rejection Rate | Counter | `rate(pricing_requests_total{type="rejected"}[5m])` |
| P95 Calculation Duration | Histogram | `histogram_quantile(0.95, ...)` |
| Active In-Flight Requests | Gauge | `pricing_requests_active` |
| Product Type Distribution | Counter by tag | `pricing_product_requests` by `product` label |
| JVM Heap Usage | JVM metric | `jvm_memory_used_bytes / jvm_memory_max_bytes` |
| DB Connection Pool | HikariCP | `hikaricp_connections_active` |
| HTTP Request Rate | Spring MVC | `rate(http_server_requests_seconds_count[5m])` |
| HTTP Error Rate | Spring MVC | `5xx / total × 100` |
| Loan Amount Distribution | Distribution | `pricing_loan_amount_requested_rupees_bucket` |
| Log Errors (Loki) | LogQL | `count_over_time({...} \| json \| level="ERROR" [5m])` |

### container-nodes-pods-utilization.json

Infrastructure dashboard with panels for:
- Container CPU and memory usage (via cAdvisor)
- Host CPU, memory, disk, and network metrics (via Node Exporter)
- Docker container count and status

---

## Grafana Features Used

### Variables

Dashboard variables allow filtering:
- Select time range
- Filter by `instance` (for multi-instance deployments)
- Filter by `environment`

### Annotations

Alert state changes appear as vertical lines on time-series panels, correlating metric anomalies with alert firings.

### Alert Links

Grafana 11's **trace-to-metrics** feature (`traceToMetrics`) allows jumping from a Tempo trace directly to a Grafana metrics panel filtered by the trace's time range.

### Log-Metric Correlation

Using Grafana's **"Derived Fields"** in the Loki datasource, `traceId` values in log entries become clickable links that open the corresponding trace in Tempo.

---

## Environment Variables

```yaml
environment:
  GF_SECURITY_ADMIN_USER: admin
  GF_SECURITY_ADMIN_PASSWORD: Bank@grafana123     # CHANGE IN PRODUCTION
  GF_AUTH_ANONYMOUS_ENABLED: "false"
  GF_ANALYTICS_REPORTING_ENABLED: "false"        # Disable phone-home
  GF_ANALYTICS_CHECK_FOR_UPDATES: "false"
  GF_USERS_DEFAULT_THEME: dark
  GF_FEATURE_TOGGLES_ENABLE: "traceToMetrics"
```

---

## Alerting

Grafana can also define alerts directly in dashboards (Grafana Managed Alerts). In this setup, alerts are managed by Prometheus (`prometheus/alerts/`) and would be routed through Alertmanager (not configured in the dev stack).

See [AlertingRules.md](./AlertingRules.md) for Prometheus alert rule documentation.

---

## Cross-References

- [Prometheus.md](./Prometheus.md) — metrics data source
- [Loki.md](./Loki.md) — log data source
- [OpenTelemetry.md](./OpenTelemetry.md) — trace data source (Tempo)
- [AlertingRules.md](./AlertingRules.md) — alert definitions
- [DockerCompose.md](../docker/DockerCompose.md) — Grafana container configuration
