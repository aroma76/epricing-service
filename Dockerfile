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

# Security: Non-root user for banking compliance
RUN addgroup -S epricing && adduser -S epricing -G epricing

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

# Container healthcheck
HEALTHCHECK --interval=30s \
            --timeout=10s \
            --start-period=60s \
            --retries=3 \
            CMD wget --quiet --tries=1 --spider http://localhost:8081/actuator/health || exit 1

# Container-aware JVM tuning flags
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+UseG1GC \
               -XX:+HeapDumpOnOutOfMemoryError \
               -XX:HeapDumpPath=/app/logs/heapdump.hprof \
               -Djava.security.egd=file:/dev/./urandom \
               -Dspring.profiles.active=docker"

# Launch application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
