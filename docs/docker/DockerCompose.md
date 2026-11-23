# Docker Compose — Observability Stack Orchestration

**File:** [`docker-compose.yml`](../../docker-compose.yml)  
**Command:** `docker-compose up --build`  
**Detailed Container Deep-Dive:** See **[`CONTAINERS_GUIDE.md`](./CONTAINERS_GUIDE.md)** for a complete study breakdown of all 17 containers.  
**Messaging Architecture:** See **[`KafkaKRaftBroker.md`](../messaging/KafkaKRaftBroker.md)** for Apache Kafka KRaft broker specifications.

---

## Purpose

`docker-compose.yml` defines the complete observability and event-driven banking stack as a single declarative file. One command starts all 17 services with proper networking, volumes, health checks, and dependencies.

---

## Services Overview

| Service | Image | Ports | Purpose |
|---|---|---|---|
| `yugabytedb` | `yugabytedb/yugabyte:2.20.1.0-b97` | `5433, 7000, 9000, 15433` | Distributed SQL database (PostgreSQL-compatible) |
| `epricing-service` | Built from Dockerfile | `8080`, `8089` | Core Spring Boot application & Actuator |
| `prometheus` | `prom/prometheus:v2.53.0` | `9090` | Metrics time-series database |
| `grafana` | `grafana/grafana:11.1.0` | `3000` | Dashboards and visualization |
| `loki` | `grafana/loki:3.1.0` | `3100` | Log aggregation |
| `promtail` | `grafana/promtail:3.1.0` | — | Log shipper (reads files → Loki) |
| `tempo` | `grafana/tempo:2.5.0` | `3200` | Distributed tracing backend |
| `otel-collector` | `otel/opentelemetry-collector-contrib:0.107.0` | `4317`, `4318`, `8888`, `8889` | Telemetry pipeline |
| `traffic-generator` | `alpine/curl:latest` | — | Automatic traffic simulation |
| `cadvisor` | `gcr.io/cadvisor/cadvisor:v0.49.1` | `8085` | Container resource metrics |
| `node-exporter` | `prom/node-exporter:v1.8.1` | `9100` | Host OS metrics |
| `alertmanager` | `prom/alertmanager:v0.27.0` | `9093` | Alert routing, grouping & deduplication |
| `webhook-logger` | `python:3.11-alpine` | `5001` | Local alert notification receiver |
| `temporal-db` | `postgres:15-alpine` | `5432` | Workflow engine persistence database |
| `temporal` | `temporalio/auto-setup:1.24.1` | `7233` | Durable workflow engine |
| `temporal-ui` | `temporalio/ui:2.26.0` | `8233` | Temporal workflow web console |
| `kafka` | `apache/kafka:3.8.0` | `9092`, `29092` | Apache Kafka KRaft event streaming broker |

---

## Network Configuration

```yaml
networks:
  epricing-network:
    driver: bridge
    ipam:
      config:
        - subnet: 172.20.0.0/16
```

All services join `epricing-network`. Docker's internal DNS resolves service names as hostnames:
- `epricing-service:8080` — app API
- `prometheus:9090` — Prometheus
- `loki:3100` — Loki push endpoint
- `kafka:29092` — Kafka internal broker listener
- `temporal:7233` — Temporal gRPC frontend

The custom `/16` subnet prevents IP conflicts with other Docker Compose stacks.

---

## Volumes

```yaml
volumes:
  yugabyte-data:        # YugabyteDB Distributed SQL persistent storage
  prometheus-data:      # Prometheus TSDB — metric history persists container restarts
  grafana-data:         # Grafana dashboards, settings, users
  loki-data:            # Loki log chunks and index
  epricing-logs:        # Shared log volume: epricing-service writes, promtail reads
  alertmanager-data:    # Alertmanager silences and notification state
  tempo-data:           # Grafana Tempo distributed trace storage
  promtail-positions:   # Promtail read positions (deduplication across restarts)
  temporal-pg-data:     # Temporal PostgreSQL persistence
  kafka-data:           # Apache Kafka KRaft persistent event storage
```

Without named volumes, all database state, telemetry histories, and event streaming logs are lost on `docker-compose down`.

---

## Service Details

### epricing-service

```yaml
epricing-service:
  build:
    context: .
    dockerfile: Dockerfile
  ports:
    - "8080:8080"
    - "8089:8081"   # Host port 8089 maps to internal Actuator port 8081
  environment:
    SPRING_PROFILES_ACTIVE: docker
    OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4318
    OTEL_SERVICE_NAME: epricing-service
    MANAGEMENT_OTLP_METRICS_EXPORT_URL: http://otel-collector:4318/v1/metrics
    LOGGING_FILE_PATH: /app/logs
    KAFKA_BOOTSTRAP_SERVERS: kafka:29092
    KAFKA_ENABLED: "true"
    KAFKA_TOPIC: loan-pricing-events
    TEMPORAL_ENABLED: "true"
    TEMPORAL_TARGET: temporal:7233
  volumes:
    - epricing-logs:/app/logs       # Shared with promtail
  depends_on:
    otel-collector:
      condition: service_started
    yugabytedb:
      condition: service_healthy
    temporal:
      condition: service_started
    kafka:
      condition: service_healthy
  deploy:
    resources:
      limits:
        memory: 512M
        cpus: '1.0'
  restart: unless-stopped
```

Resource limits prevent the container from consuming all host resources. `512M` memory limit forces the JVM to respect container bounds via `-XX:+UseContainerSupport`.

### kafka (KRaft Mode)

```yaml
kafka:
  image: apache/kafka:3.8.0
  container_name: epricing-kafka
  ports:
    - "9092:9092"
  environment:
    KAFKA_NODE_ID: 1
    KAFKA_PROCESS_ROLES: broker,controller
    KAFKA_LISTENERS: PLAINTEXT://:29092,PLAINTEXT_HOST://:9092,CONTROLLER://:9093
    KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:9092
    KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
    KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT,CONTROLLER:PLAINTEXT
    KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
    KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
    KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
    KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
    KAFKA_NUM_PARTITIONS: 3
    KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"
    KAFKA_LOG_DIRS: /var/lib/kafka/data
  volumes:
    - kafka-data:/var/lib/kafka/data
  networks:
    epricing-network:
      aliases:
        - kafka
  restart: unless-stopped
  healthcheck:
    test: ["CMD-SHELL", "/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list || exit 1"]
    interval: 10s
    timeout: 5s
    retries: 5
    start_period: 20s
```

Detailed technical guide: [Kafka KRaft Broker Documentation](../messaging/KafkaKRaftBroker.md).

### prometheus

```yaml
prometheus:
  image: prom/prometheus:v2.53.0
  ports:
    - "9090:9090"
  volumes:
    - ./prometheus.yml:/etc/prometheus/prometheus.yml:ro
    - ./prometheus/alerts:/etc/prometheus/alerts:ro
    - prometheus-data:/prometheus
  command:
    - '--config.file=/etc/prometheus/prometheus.yml'
    - '--storage.tsdb.path=/prometheus'
    - '--storage.tsdb.retention.time=30d'
    - '--web.enable-lifecycle'           # Hot-reload config: POST /-/reload
    - '--web.enable-remote-write-receiver'
```

`--web.enable-remote-write-receiver` allows OTel Collector to push metrics via Prometheus Remote Write protocol.

### grafana

```yaml
grafana:
  image: grafana/grafana:11.1.0
  ports:
    - "3000:3000"
  environment:
    GF_SECURITY_ADMIN_PASSWORD: Bank@grafana123
    GF_FEATURE_TOGGLES_ENABLE: "traceToMetrics"
  volumes:
    - grafana-data:/var/lib/grafana
    - ./grafana/provisioning/datasources:/etc/grafana/provisioning/datasources:ro
    - ./grafana/provisioning/dashboards:/etc/grafana/provisioning/dashboards:ro
    - ./grafana/dashboards:/var/lib/grafana/dashboards:ro
```

All datasources and dashboards are auto-provisioned at startup from mounted configuration files. No manual Grafana setup needed.

### promtail

```yaml
promtail:
  image: grafana/promtail:3.1.0
  volumes:
    - ./promtail-config.yml:/etc/promtail/config.yml:ro
    - epricing-logs:/var/log/epricing:ro     # Reads logs written by epricing-service
    - /var/run/docker.sock:/var/run/docker.sock:ro   # Docker service discovery
```

The `epricing-logs` volume is shared between `epricing-service` (write) and `promtail` (read). Promtail reads `/var/log/epricing/*.log` and ships to Loki.

### traffic-generator

```yaml
traffic-generator:
  image: alpine/curl:latest
  command: >
    sh -c '
      sleep 20
      while true; do
        curl -s -X POST http://epricing-service:8080/api/v1/pricing \
          -H "Content-Type: application/json" \
          -d "{\"customer_id\":\"CUST001234\",\"product_type\":\"HOME_LOAN\",...}" > /dev/null
        sleep 2
      done
    '
```

Automatically sends pricing requests every 2 seconds. This ensures Grafana dashboards have live, realistic data immediately after `docker-compose up` — no need to manually hit the API.

### cadvisor and node-exporter

Infrastructure metric exporters:
- **cAdvisor**: Container-level CPU, memory, network metrics → scraped by Prometheus
- **Node Exporter**: Host OS metrics (CPU, disk, network) → scraped by Prometheus

Both are mounted with read-only host paths to prevent container privilege escalation.

---

## Data Flow Summary

```
Customer Request → epricing-service :8080
                                     ↓ write logs
                            /app/logs/epricing-service.log
                                     ↓ tail
                                promtail
                                     ↓ push
                                loki :3100
                                     ↓ datasource
                                grafana :3000

epricing-service → OTLP HTTP :4318 → otel-collector
                                           ↓ prometheus exporter
                                      prometheus :9090 ← scrape :8081/actuator/prometheus
                                           ↓ datasource
                                      grafana :3000

epricing-service → Kafka Producer :29092 → Apache Kafka KRaft (loan-pricing-events)
                                                ↓ stream
                                           Downstream Consumers / Lakehouse
```

---

## Common Commands

```bash
# Start all 17 platform containers
docker-compose up --build

# Start in background
docker-compose up --build -d

# View logs for specific service
docker-compose logs -f epricing-service
docker-compose logs -f kafka

# Restart just the app (after code change)
docker-compose build epricing-service
docker-compose up -d epricing-service

# Stop and remove containers (keep volumes)
docker-compose down

# Stop and remove everything including data
docker-compose down -v

# Reload Prometheus config without restart
curl -X POST http://localhost:9090/-/reload
```

---

## Cross-References

- [CONTAINERS_GUIDE.md](./CONTAINERS_GUIDE.md) — Comprehensive deep-dive on all 17 containers
- [KafkaKRaftBroker.md](../messaging/KafkaKRaftBroker.md) — Kafka KRaft broker architecture
- [LoanPricingEventStreaming.md](../messaging/LoanPricingEventStreaming.md) — Event streaming specification
- [ProducerFailFast.md](../messaging/ProducerFailFast.md) — Fail-fast producer configuration
- [Dockerfile.md](./Dockerfile.md) — epricing-service image build
- [Prometheus.md](../monitoring/Prometheus.md) — Prometheus configuration
- [Grafana.md](../monitoring/Grafana.md) — Grafana setup
- [Loki.md](../monitoring/Loki.md) — Loki configuration
- [OpenTelemetry.md](../monitoring/OpenTelemetry.md) — OTel Collector
