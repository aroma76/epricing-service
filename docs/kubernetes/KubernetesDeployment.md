# Kubernetes Deployment

> **Status: In Progress (Phase 1-B Kubernetes Architecture)**

Kubernetes manifests are organized under the [`k8s/`](../../k8s/) directory for declarative enterprise deployment.

---

## 1. Enterprise Namespace & Governance (`k8s/00-namespace.yml`)

The dedicated **`epricing`** namespace provides multi-tenant isolation and strict Pod Security Standards:

* **Pod Security Standards:** `pod-security.kubernetes.io/enforce: restricted` (ensures non-root, read-only root filesystems, drop all Linux capabilities).
* **ResourceQuota (`epricing-quota`):** Protects cluster stability with CPU limits (8 cores max) and Memory limits (16 Gi max).
* **LimitRange (`epricing-limit-range`):** Injects default resource requests (`250m` CPU / `256Mi` RAM) and enforces bounds on individual containers.

---

## 2. Role-Based Access Control (RBAC) (`k8s/01-rbac.yml`)

* **Application ServiceAccount (`epricing-service-sa`):** Least-privilege identity used by `epricing-service` pods.
* **Developer Read-Only Role (`epricing-developer-role`):** Scoped access allowing developers to inspect logs (`kubectl logs`) and forward ports without destructive cluster permissions.
* **Prometheus Discovery Role (`epricing-prometheus-scraper-role`):** Permits Prometheus Operator to discover and scrape `/actuator/prometheus` metrics dynamically.

> 📖 **Full Architecture & RBAC Policy Matrix:** See [NamespaceAndRBAC.md](./NamespaceAndRBAC.md) for detailed verification commands (`kubectl auth can-i`).

---

## 3. Core Workload Manifests

### Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: epricing-service
  namespace: epricing
spec:
  replicas: 3
  selector:
    matchLabels:
      app: epricing-service
  template:
    metadata:
      labels:
        app: epricing-service
    spec:
      containers:
        - name: epricing-service
          image: registry.bank.com/epricing-service:1.0.0
          ports:
            - containerPort: 8080
            - containerPort: 8081
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "prod"
            - name: OTEL_EXPORTER_OTLP_ENDPOINT
              value: "http://otel-collector.monitoring:4318"
          resources:
            requests:
              memory: "256Mi"
              cpu: "250m"
            limits:
              memory: "512Mi"
              cpu: "1000m"
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8081
            initialDelaySeconds: 60
            periodSeconds: 30
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8081
            initialDelaySeconds: 30
            periodSeconds: 10
```

### Service

```yaml
apiVersion: v1
kind: Service
metadata:
  name: epricing-service
  namespace: epricing
spec:
  selector:
    app: epricing-service
  ports:
    - name: api
      port: 8080
      targetPort: 8080
    - name: management
      port: 8081
      targetPort: 8081
  type: ClusterIP
```

### ConfigMap

Configuration that differs between environments would be stored in a `ConfigMap`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: epricing-config
  namespace: epricing
data:
  SPRING_PROFILES_ACTIVE: "prod"
  OTEL_SERVICE_NAME: "epricing-service"
  OTEL_EXPORTER_OTLP_ENDPOINT: "http://otel-collector.monitoring:4318"
```

### Secret

Sensitive values (database credentials, API keys) would use Kubernetes Secrets (ideally External Secrets Operator backed by HashiCorp Vault):

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: epricing-secrets
  namespace: epricing
type: Opaque
data:
  SPRING_DATASOURCE_PASSWORD: <base64-encoded-password>
```

### HorizontalPodAutoscaler

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: epricing-hpa
  namespace: epricing
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: epricing-service
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
```

---

## Observability in Kubernetes

For production Kubernetes, the observability stack would be deployed separately:

| Component | Kubernetes Deployment |
|---|---|
| Prometheus | `prometheus-operator` (Helm chart) |
| Grafana | `grafana` (Helm chart) |
| Loki | `loki-stack` (Helm chart) |
| OTel Collector | `opentelemetry-collector` (Helm chart) |
| Promtail | `loki-stack` sidecar DaemonSet |

Prometheus would discover epricing-service pods automatically via `ServiceMonitor` CRD.

---

## Cross-References

- [Dockerfile.md](../docker/Dockerfile.md) — container image
- [DockerCompose.md](../docker/DockerCompose.md) — local dev orchestration
- [ApplicationConfig.md](../configuration/ApplicationConfig.md) — Spring profiles
