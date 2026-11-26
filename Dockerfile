# ╔══════════════════════════════════════════════════════════════════════════╗
# ║  Dockerfile — Container Image Definition                                 ║
# ╠══════════════════════════════════════════════════════════════════════════╣
# ║  WHY THIS FILE EXISTS:                                                   ║
# ║  A Dockerfile is a recipe for building a Docker image.                  ║
# ║  It takes your application and packages it with a Java runtime          ║
# ║  into a self-contained, portable container image.                       ║
# ║                                                                          ║
# ║  MULTI-STAGE BUILD:                                                      ║
# ║  Stage 1 (builder): Maven compiles and packages the fat JAR.            ║
# ║  Stage 2 (runtime): Only the JRE and compiled fat JAR are copied.        ║
# ║  Result: Lean, minimal ~180MB image instead of 600MB+ build environment. ║
# ╚══════════════════════════════════════════════════════════════════════════╝

# ═══════════════════════════════════════════════════════════
# STAGE 1: Build Stage (Maven + OpenJDK 21)
# ═══════════════════════════════════════════════════════════
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Copy pom.xml first for layer caching optimization
COPY pom.xml .

# Pre-download dependencies (uses Docker cache if pom.xml is unchanged)
RUN mvn dependency:resolve -B --no-transfer-progress

# Copy source code and build fat JAR
COPY src/ src/
RUN mvn clean package -DskipTests -B --no-transfer-progress

# ═══════════════════════════════════════════════════════════
# STAGE 2: Runtime Stage (Minimal JRE 21)
# ═══════════════════════════════════════════════════════════
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: Non-root user with explicit deterministic UID/GID (matches Kubernetes securityContext)
RUN addgroup -g 10001 -S epricing && adduser -u 10001 -S epricing -G epricing

WORKDIR /app

# Copy fat JAR from builder stage
COPY --from=builder --chown=epricing:epricing /build/target/*.jar /app/app.jar

# Create log directory for Promtail log shipping
RUN mkdir -p /app/logs && chown epricing:epricing /app/logs

# Switch to non-root user
USER epricing

# Expose API and Management ports
# EXPOSE 8080: Application port — handles API requests
EXPOSE 8080
# EXPOSE 8081: Management port — Actuator, Prometheus scraping
EXPOSE 8081

# Container healthcheck (uses IPv4 loopback to avoid Alpine localhost/IPv6 resolution ambiguity)
HEALTHCHECK --interval=30s \
            --timeout=10s \
            --start-period=180s \
            --retries=5 \
            CMD wget --quiet --tries=1 --spider http://127.0.0.1:8081/actuator/health/liveness || exit 1

# Default Spring profile (overridable via environment variable without mutating JAVA_OPTS)
ENV SPRING_PROFILES_ACTIVE=docker

# Container-aware JVM tuning flags (bounded 217 realistic / 260 ceiling threads inside 2048M limit)
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:InitialRAMPercentage=50.0 \
               -XX:MaxRAMPercentage=60.0 \
               -Xss512k \
               -XX:MaxMetaspaceSize=256m \
               -XX:ReservedCodeCacheSize=128m \
               -XX:MaxDirectMemorySize=128m \
               -XX:+UseG1GC \
               -XX:+ExitOnOutOfMemoryError \
               -XX:+HeapDumpOnOutOfMemoryError \
               -XX:HeapDumpPath=/app/logs/heapdump.hprof \
               -Djava.security.egd=file:/dev/./urandom"

# Launch application with exec to ensure SIGTERM reaches JVM process for graceful shutdown
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
