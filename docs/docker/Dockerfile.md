# Dockerfile — Container Image Definition

**File:** [`Dockerfile`](../../Dockerfile)
**Base Image (Build Stage):** `maven:3.9-eclipse-temurin-21-alpine`
**Base Image (Runtime Stage):** `eclipse-temurin:21-jre-alpine`

---

## Purpose

The Dockerfile packages the ePricing Spring Boot application as a portable, self-contained Docker image using a **multi-stage build** pattern for minimal image size.

---

## Multi-Stage Build

```dockerfile
# ── Stage 1: Build ──────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build
COPY pom.xml .
RUN mvn dependency:resolve -B --no-transfer-progress   # Cache layer: deps only
COPY src/ src/
RUN mvn clean package -DskipTests -B --no-transfer-progress

# ── Stage 2: Runtime ─────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S epricing && adduser -S epricing -G epricing  # Non-root user
WORKDIR /app
COPY --from=builder --chown=epricing:epricing /build/target/*.jar /app/app.jar
RUN mkdir -p /app/logs && chown epricing:epricing /app/logs
USER epricing
```

**Why multi-stage?**

| Stage | Purpose | Size Contribution |
|---|---|---|
| Stage 1 (builder) | Maven + JDK + all dependencies | ~600MB (discarded) |
| Stage 2 (runtime) | JRE + compiled fat JAR only | ~180MB (final image) |

Only Stage 2 is kept in the final image — Maven, JDK compiler, and source code are discarded.

---

## Docker Layer Caching Optimization

```dockerfile
COPY pom.xml .
RUN mvn dependency:resolve -B    # ← Cached if pom.xml unchanged
COPY src/ src/
RUN mvn clean package -DskipTests
```

By copying `pom.xml` first and resolving dependencies before copying source code, Docker can cache the dependency layer. On subsequent builds where only source code changed:

- Layer 1 (`pom.xml` copy): Cache HIT
- Layer 2 (`mvn dependency:resolve`): Cache HIT (no `pom.xml` change)
- Layer 3 (`src/` copy): Cache MISS — rebuild from here

This dramatically speeds up CI/CD build times when only application code changes.

---

## Security: Non-Root User

```dockerfile
RUN addgroup -S epricing && adduser -S epricing -G epricing
USER epricing
```

Running as a non-root user is a **banking security requirement** (CIS Docker Benchmark). If a container is compromised, the attacker does not have root privileges on the host system.

- `-S`: System user (no login shell, no home directory by default)
- `--chown=epricing:epricing`: JAR and log directory are owned by the service user

---

## Ports

```dockerfile
EXPOSE 8080    # API port — handles customer-facing requests
EXPOSE 8081    # Management port — Actuator endpoints (health, prometheus)
```

`EXPOSE` is documentation; actual port mapping is done in `docker-compose.yml` or Kubernetes `Service`.

---

## Health Check

```dockerfile
HEALTHCHECK --interval=30s \
            --timeout=10s \
            --start-period=60s \
            --retries=3 \
            CMD wget --quiet --tries=1 --spider http://localhost:8081/actuator/health || exit 1
```

| Parameter | Value | Reason |
|---|---|---|
| `--interval` | 30s | Check every 30 seconds |
| `--timeout` | 10s | Mark unhealthy if no response in 10s |
| `--start-period` | 60s | Spring Boot startup grace period |
| `--retries` | 3 | 3 consecutive failures → unhealthy |

Uses `wget` (pre-installed in Alpine images) to check the Spring Boot Actuator health endpoint.

---

## JVM Configuration

```dockerfile
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+UseG1GC \
               -XX:+HeapDumpOnOutOfMemoryError \
               -XX:HeapDumpPath=/app/logs/heapdump.hprof \
               -Djava.security.egd=file:/dev/./urandom \
               -Dspring.profiles.active=docker"
```

| Flag | Purpose |
|---|---|
| `UseContainerSupport` | JVM reads container memory limits (not host memory) |
| `MaxRAMPercentage=75.0` | Heap max = 75% of container memory limit (512MB → ~384MB heap) |
| `UseG1GC` | G1 garbage collector — better latency for heap < 32GB |
| `HeapDumpOnOutOfMemoryError` | Capture heap dump on OOM for post-mortem analysis |
| `HeapDumpPath=/app/logs/heapdump.hprof` | Save heap dump to volume (retrievable after container death) |
| `java.security.egd=file:/dev/./urandom` | Faster UUID/random generation in containers (avoids `/dev/random` blocking) |
| `spring.profiles.active=docker` | Activates Docker logging profile in `logback-spring.xml` |

---

## Build and Run Commands

```bash
# Build image
docker build -t epricing-service:1.0.0 .

# Run standalone (without Compose)
docker run -p 8080:8080 -p 8081:8081 epricing-service:1.0.0

# Run via Docker Compose (recommended)
docker-compose up --build epricing-service
```

---

## Cross-References

- [DockerCompose.md](./DockerCompose.md) — full observability stack orchestration
- [ApplicationConfig.md](../configuration/ApplicationConfig.md) — Spring profile configuration
- [LoggingStrategy.md](../logging/LoggingStrategy.md) — Docker profile logging config
