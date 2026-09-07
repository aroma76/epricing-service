# Prometheus Alerting Rules

**File:** [`prometheus/alerts/application-alerts.yml`](../../prometheus/alerts/application-alerts.yml)
**Loaded by:** `prometheus.yml` via `rule_files: ["/etc/prometheus/alerts/*.yml"]`

---

## Overview

Prometheus evaluates alerting rules every `evaluation_interval` (15s). If a rule's condition is `true` for the `for` duration, the alert fires and is sent to Alertmanager (not configured in dev — would route to Slack/PagerDuty in production).

**Alert lifecycle:**
```
INACTIVE → PENDING (condition true) → FIRING (after `for` duration) → RESOLVED
```

---

## Alert Groups

### Group 1: Application Health (`epricing-application-health`)

#### EPricingServiceDown

| Field | Value |
|---|---|
| **Severity** | `critical` |
| **For** | `1m` |
| **PromQL** | `up{job="epricing-service"} == 0` |

The `up` metric is automatically created by Prometheus for each scrape target. `up=0` means the last scrape failed.

**Why 1 minute?** Prevents false alerts from 1–2 second network blips. If the service is genuinely down, it will still be down after 60 seconds.

**Runbook:** `https://wiki.Bank.com/runbooks/epricing-service-down`

---

#### EPricingHighErrorRate

| Field | Value |
|---|---|
| **Severity** | `critical` |
| **For** | `3m` |
| **Threshold** | > 5% error rate |

```promql
(
  rate(http_server_requests_seconds_count{
    job="epricing-service", status=~"5.."
  }[5m])
  /
  rate(http_server_requests_seconds_count{
    job="epricing-service"
  }[5m])
) * 100 > 5
```

5% error rate = 1 in 20 customers experiencing failures. `rate()` is used instead of `count()` because rate is unaffected by counter resets (app restarts).

**Alert message:** `"Error rate is X.XX%. More than 1 in 20 requests are failing."`

---

#### EPricingHighLatency

| Field | Value |
|---|---|
| **Severity** | `warning` |
| **For** | `5m` |
| **Threshold** | P95 > 2 seconds |

```promql
histogram_quantile(0.95,
  rate(http_server_requests_seconds_bucket{
    job="epricing-service"
  }[5m])
) > 2
```

2 second threshold is based on RBI (Reserve Bank of India) SLA guidelines for digital banking APIs. Requires `percentiles-histogram: true` in `application.yml`.

---

#### EPricingHighRejectionRate

| Field | Value |
|---|---|
| **Severity** | `warning` |
| **For** | `5m` |
| **Threshold** | > 20% rejection rate |
| **Additional team** | `risk-analytics` (for investigation) |

```promql
(
  rate(pricing_requests_total{type="rejected"}[10m])
  / rate(pricing_requests_total{type="all"}[10m])
) * 100 > 20
```

A sudden spike in rejections may indicate:
- Incorrect credit score data from bureau
- Product eligibility rules changed
- Fraud pattern (many ineligible customers in bulk)

---

### Group 2: Infrastructure (`epricing-infrastructure`)

#### EPricingHighCPU

| Field | Value |
|---|---|
| **Severity** | `warning` |
| **For** | `5m` |
| **Threshold** | > 80% CPU |

```promql
process_cpu_usage{job="epricing-service"} > 0.80
```

Above 80% CPU, GC pressure increases and latency spikes. The 20% buffer handles burst traffic without false alerts.

---

#### EPricingHighHeapUsage

| Field | Value |
|---|---|
| **Severity** | `warning` |
| **For** | `5m` |
| **Threshold** | > 85% of max heap |

```promql
(
  jvm_memory_used_bytes{job="epricing-service", area="heap"}
  / jvm_memory_max_bytes{job="epricing-service", area="heap"}
) > 0.85
```

Above 85% heap usage, Full GC becomes frequent. Above 95% → OOM crash → container killed.

---

#### EPricingDBConnectionPoolLow

| Field | Value |
|---|---|
| **Severity** | `critical` |
| **For** | `2m` |
| **Threshold** | Any pending DB connections |

```promql
hikaricp_connections_pending{job="epricing-service"} > 0
```

`hikaricp_connections_pending > 0` means requests are waiting for a DB connection — the pool is exhausted. This cascades: waiting requests → request timeouts → HTTP 500 → customer-facing errors.

Short `for: 2m` because DB pool exhaustion is immediately customer-impacting.

---

#### EPricingRequestBacklog

| Field | Value |
|---|---|
| **Severity** | `warning` |
| **For** | `3m` |
| **Threshold** | > 50 in-flight requests |

```promql
pricing_requests_active{job="epricing-service"} > 50
```

Uses the custom `pricing_requests_active` Gauge from `PricingMetrics`. If more than 50 requests are simultaneously being processed for 3+ minutes, the service is under severe load.

---

## Alert Template Variables

Prometheus alert annotations support templates:

```yaml
description: "Error rate is {{ printf \"%.2f\" $value }}%."
```

- `$value` — the current value of the alerting expression
- `$labels.instance` — the instance label of the firing target
- `{{ printf "%.2f" $value }}` — format floating point to 2 decimal places

---

## Alertmanager Integration

> **Status: Not configured in the dev stack.** In production, Alertmanager would handle:

```yaml
# prometheus.yml (production addition)
alerting:
  alertmanagers:
    - static_configs:
        - targets: ['alertmanager:9093']
```

**Alertmanager would route:**
- `severity=critical` → PagerDuty (immediate on-call notification)
- `severity=warning` → Slack `#epricing-alerts` channel
- `team_notify=risk-analytics` → Slack `#risk-analytics` channel

---

## Cross-References

- [Prometheus.md](./Prometheus.md) — Prometheus configuration
- [PricingMetrics.md](./PricingMetrics.md) — custom metrics used in alerts
- [Grafana.md](./Grafana.md) — alert visualization in dashboards
