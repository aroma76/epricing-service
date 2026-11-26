# Production Hardening & Verification Walkthrough (`epricing-service`)

This walkthrough documents the production hardening changes implemented across `epricing-service` (Spring Boot 3.3.4, Java 21 LTS, YugabyteDB/PostgreSQL, Temporal 1.24.1, Apache Kafka KRaft 3.8.0, OpenTelemetry, Docker, and cloud-agnostic Kubernetes) and the exact verification results observed after the final edits.

---

## 1. Executive Summary & Requirements Checklist

| Requirement | Scope | Status | Primary Artifacts |
|---|---|---|---|
| **R1** | Temporal Activity Concurrency Guard (`APPROVED`, `REJECTED`, `EXPIRED` -> `SKIPPED_TERMINAL_STATE`, `saveAndFlush`) | Complete | `PricingActivitiesImpl.java`, `PricingActivitiesImplTest.java`, `ApplicationContextBootstrapTest.java` |
| **R2** | Container Thread & Memory Sizing (`217` realistic / `260` ceiling <= `2048 MiB` RSS, `-Xss512k`, `-XX:MaxMetaspaceSize=256m`, `MaxRAMPercentage=60.0`, `1536M`/`2048M`) | Complete | `application.yml`, `TemporalConfig.java`, `Dockerfile`, `docker-compose.yml`, `k8s/04-deployment.yml` |
| **R3** | Customer ID Validation Pattern (`^CUST(?=[A-Z0-9-]*[A-Z0-9])[A-Z0-9-]{6,46}$` aligned with `VARCHAR(50)`) | Complete | `PricingRequestDto.java`, `PricingRequestDtoValidationTest.java`, `PricingControllerTest.java`, `TemporalPricingControllerTest.java`, `ApplicationContextBootstrapTest.java` |
| **R4** | Portable Production Deployment Hardening (`.dockerignore`, `Dockerfile`, `docker-compose.yml`, Spring/OTel/Logback configs, `k8s/00`–`08`, zero cloud-vendor lock-in) | Complete | `.dockerignore`, `Dockerfile`, `docker-compose.yml`, `application.yml`, `logback-spring.xml`, `OpenTelemetryConfig.java`, `k8s/00-namespace.yml`, `k8s/01-rbac.yml`, `k8s/02-configmap.yml`, `k8s/04-deployment.yml`, `k8s/06-hpa.yml`, `k8s/08-networkpolicy.yml` |
| **R5** | Implementation & Verification Walkthrough + Synced Sizing Docs | Complete | `docs/walkthrough.md`, `README.md`, `docs/README.md`, `docs/docker/*`, `docs/kubernetes/KubernetesDeployment.md` |

---

## 2. R1 — Temporal Activity Concurrency Guard

### Root Cause
In `src/main/java/com/bank/epricing/temporal/activity/PricingActivitiesImpl.java`, terminal state guards were asymmetric across the three state-transition activities:
- `recordQuoteApproval` checked only `EXPIRED || REJECTED` (missing `APPROVED`).
- `recordQuoteRejection` checked only `status == APPROVED` (allowing an already `EXPIRED` or `REJECTED` quote to be overwritten to `REJECTED` and re-published to Kafka).
- `recordQuoteExpiration` checked only `status == APPROVED` (allowing an already `REJECTED` or `EXPIRED` quote to be overwritten to `EXPIRED` and re-published to Kafka).
- In addition, `try/catch (OptimisticLockingFailureException)` wrapped `pricingRepository.findById(...)` rather than `pricingRepository.save(requestEntity)`.

### Implemented Fix
- Introduced `TERMINAL_STATUSES = EnumSet.of(APPROVED, REJECTED, EXPIRED)` in `PricingActivitiesImpl.java`.
  - **Scope rationale**: `TERMINAL_STATUSES` intentionally comprises the three business-terminal quote outcomes (`APPROVED`, `REJECTED`, `EXPIRED`) matching R1 verbatim. `ERROR` is a technical-failure state outside normal workflow quote termination and is not included in the business-terminal guard.
- Updated `recordQuoteApproval`, `recordQuoteRejection`, and `recordQuoteExpiration` to share the symmetric `TERMINAL_STATUSES.contains(requestEntity.getStatus())` guard:
  1. Logs a warning with the current terminal status and `requestId`.
  2. Persists an immutable `PricingAuditLog` with the activity's `.action(...)` (`RATE_APPROVED` / `RATE_REJECTED` / `RATE_EXPIRED`) and `.outcome("SKIPPED_TERMINAL_STATE")` (supported by `V8__reconcile_schema_and_range_indexes.sql` widening `outcome` to `VARCHAR(50)`).
  3. Returns early without mutating `PricingRequest`, without calling `pricingRepository.save(...)` or `saveAndFlush(...)`, and without publishing a duplicate `LoanPricingEvent` to Kafka.
- Kept `PricingActivities` method signatures (`void`) unchanged so `LoanPricingWorkflowImpl` and existing workflow stubs remain compatible.
- Replaced `pricingRepository.save(requestEntity)` with `pricingRepository.saveAndFlush(requestEntity)` inside the `try/catch (OptimisticLockingFailureException)` blocks in all three methods (`recordQuoteApproval`, `recordQuoteRejection`, `recordQuoteExpiration`). Because these methods are `@Transactional`, Hibernate would otherwise defer the `UPDATE ... WHERE version=?` SQL flush until transaction commit after the method returned, making an in-method `try/catch (OptimisticLockingFailureException)` around `save()` unreachable in real JPA. Calling `saveAndFlush(...)` forces immediate SQL execution and deterministic optimistic-lock detection inside the `try/catch` block.
- Guarded `CompletableFuture` returns from `pricingEventProducer.publishPricingEvent(...)` against `null` in both `PricingActivitiesImpl.publishStatusEvent` and `PricingService.dispatchPricingEvent`, preventing spurious `NullPointerException` stack traces in unit mocks while preserving async `whenComplete` error logging.

### Verification Coverage
- **Unit tests (`PricingActivitiesImplTest.java`, 18 tests)**: Covers `EXPIRED -> recordQuoteRejection`, `REJECTED -> recordQuoteRejection`, `REJECTED -> recordQuoteExpiration`, `EXPIRED -> recordQuoteExpiration`, `APPROVED -> recordQuoteApproval`, verification that neither `save(...)` nor `saveAndFlush(...)` is invoked on `SKIPPED_TERMINAL_STATE`, `OptimisticLockingFailureException` propagation on `saveAndFlush` conflicts, and safe handling of a `null` `CompletableFuture` from `PricingEventProducer`.
- **Spring `@SpringBootTest` + Real H2 JPA Integration (`ApplicationContextBootstrapTest.java`)**:
  - `testRealJpaTerminalStateGuardsInPricingActivities`: Invokes `PricingActivitiesImpl` through its Spring `@Transactional` proxy against H2 in PostgreSQL mode (`DatabaseMigrationInitializer` V1–V8), verifying that terminal rows keep their original `status`, `errorMessage`, and `@Version` (`0L` or `1L`) while `SKIPPED_TERMINAL_STATE` audit entries persist cleanly.
  - `testRealJpaConcurrentOptimisticLockingInPricingActivities`: Exercises a real multi-threaded stale-version race against H2 + `JpaTransactionManager`, proving `ObjectOptimisticLockingFailureException` fires deterministically inside `saveAndFlush` within `PricingActivitiesImpl`, rolls back the stale transaction, leaves DB `status=APPROVED` and `version=1L` intact, and emits zero phantom `PricingAuditLog` rows or `afterCommit()` Kafka events.

---

## 3. R3 — Customer ID Validation Pattern

### Root Cause
`PricingRequestDto.customerId` previously enforced `@Pattern(regexp = "^CUST[0-9]{6,10}$")`, which blocked enterprise and integration-test identifiers such as `CUST-LIVE-001`, `CUST-ACT-001`, and `CUST-LIVE-COLLIDE-01` at REST validation (`POST /api/v1/pricing` and `POST /api/v1/workflow/pricing/start`), even though the database schema (`V1__create_pricing_requests_table.sql`, `V2__create_pricing_audit_logs_table.sql`, `PricingRequest.java`, `PricingAuditLog.java`) allocates `VARCHAR(50)`.

### Implemented Fix
Updated `src/main/java/com/bank/epricing/dto/PricingRequestDto.java`:
```java
@NotBlank(message = "Customer ID is required")
@Pattern(
    regexp = "^CUST(?=[A-Z0-9-]*[A-Z0-9])[A-Z0-9-]{6,46}$",
    message = "Customer ID must start with CUST followed by 6-46 uppercase alphanumeric characters or hyphens (total 10-50 chars)"
)
@JsonProperty("customer_id")
private String customerId;
```
- **Prefix**: Preserves mandatory `CUST` prefix (4 chars).
- **Character set & length**: Allows 6–46 uppercase alphanumeric characters or hyphens after `CUST`, keeping total length in `[10, 50]` to match `VARCHAR(50)` without requiring a schema migration.
- **Lookahead `(?=[A-Z0-9-]*[A-Z0-9])`**: Prevents degenerate all-hyphen suffixes such as `CUST------`.
- **Rejected inputs**: Missing prefix (`INVALID_ID`, `USER000001`), lowercase (`cust000001`, `CUST-live-001`), underscores/spaces (`CUST_LIVE_001`, `CUST 00001`), all-hyphens (`CUST------`), too short (`CUST01`, `CUST00001`), and `> 50` chars.

### Verification Coverage
- **`PricingRequestDtoValidationTest.java` (19 parameterized tests)**: Validates boundary lengths (10 and 50 valid; 9 and 51 rejected) and character rules via Jakarta Bean Validation.
- **`PricingControllerTest.java` (9 tests)** & **`TemporalPricingControllerTest.java` (7 tests)**: MockMvc coverage with `GlobalExceptionHandler` confirming HTTP 201 / HTTP 202 for `CUST-LIVE-001` and HTTP 400 (`PRICING_VALIDATION_FAILED`) for invalid IDs.
- **`ApplicationContextBootstrapTest.java`**: Live `TestRestTemplate` HTTP checks against `/api/v1/pricing`.

---

## 4. R2 — Thread Inventory & JVM/Container Memory Sizing

### Thread Footprint Analysis (Temporal SDK 1.27.0 + Dual Tomcat Connectors)
With default settings, Tomcat (`max=200` shared across both `:8080` business and `:8081` management connectors via `ServletManagementWebServerFactoryCustomizer`) and Temporal SDK 1.27.0 platform `ThreadPoolExecutor`s (`WorkerFactory` `maxWorkflowThreadCount=600` + `PollTaskExecutor`s for 200 workflow tasks + 200 activities + local activities + pollers + gRPC) allow the JVM to spin up hundreds of threads and breach container memory limits under burst load.

We tightened both Tomcat and Temporal platform thread pools in `application.yml` and `TemporalConfig.java` so realistic burst concurrency stays `<= 217` threads and even an absolute 100% simultaneous theoretical saturation across every pool (`260` threads) remains strictly inside the `2048 MiB` container memory limit:

| Subsystem | Configuration Source | Configured Bound | Effective Threads (Idle -> Burst Max) |
|---|---|---|---|
| Tomcat Business HTTP (`:8080`) | `application.yml` (`server.tomcat.threads`) | `min-spare: 8`, `max: 50`, `accept-count: 100` | `12` idle -> `54` max (`50` workers + `4` poller/acceptor/async) |
| Tomcat Management (`:8081`) | `management.server.port: 8081` (inherits `server.tomcat.threads`) | `min-spare: 8`, `max: 50` (Actuator/Prometheus only) | `11` idle -> `54` theoretical max |
| Temporal Worker & gRPC | `TemporalConfig.java` + `application.yml` (`epricing.temporal.worker`) | `max-workflow-threads: 50`, `workflow-cache-size: 200`, `max-concurrent-workflow-tasks: 25`, `max-concurrent-activities: 25`, `max-concurrent-local-activities: 10`, `workflow-task-pollers: 2`, `activity-task-pollers: 2` | `~16` idle -> `120` max (`50 + 25 + 2 + 25 + 2 + 10 + 6` gRPC) |
| HikariCP + YugabyteDB Smart Driver | `application.yml` (`epricing-pool`) | `maximum-pool-size: 20`, `minimum-idle: 5` | `3` |
| Kafka Producer | `KafkaProducerConfig.java` | Single `KafkaTemplate` producer | `2` |
| OpenTelemetry SDK + OTLP Exporter | `OpenTelemetryConfig.java` | `BatchSpanProcessor` + OkHttp HTTP/protobuf | `6` |
| Logback `ASYNC_FILE` | `logback-spring.xml` | `queueSize=512` | `1` |
| JVM System (G1GC, C1/C2 JIT, Ref/Cleaner) | `JAVA_OPTS` (`-XX:+UseG1GC`, 2 CPUs) | Java 21 JRE | `20` |
| **Total Realistic Burst (`:8081` idle)** | — | — | **`217` threads (`108.5 MiB` @ `-Xss512k`)** |
| **Absolute 100% Simultaneous Ceiling (`:8081` also saturated)** | — | — | **`260` threads (`130.0 MiB` @ `-Xss512k`)** |

### Memory Budget (`1536M` Reservation / `2048M` Limit)
Previously, containers ran with `1024M` (`1024Mi`) limits, `-XX:MaxRAMPercentage=75.0` (`768 MiB` heap), and Linux default `-Xss1m` with uncapped Metaspace — causing worst-case RSS to exceed the `1024M` cgroup limit and trigger Linux OOM kills (`Exit 137`). Even at `2048M`, keeping `MaxRAMPercentage=75.0` (`1536 MiB` heap) would leave only `512 MiB` for non-heap memory, below worst-case non-heap demand.

Setting **`-XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=60.0`** alongside bounded thread pools and explicit native caps guarantees safe headroom inside **`1536M` / `2048M`** even under 100% simultaneous worst-case saturation:

| Memory Region | JVM Flag / Source | Typical Steady-State | Worst-Case (217 Realistic / 260 Ceiling) |
|---|---|---:|---:|
| JVM Heap | `-XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=60.0` | `700–1024 MiB` | `1228.8 MiB` |
| Thread Stacks | `-Xss512k` (`217` realistic / `260` ceiling) | `35–60 MiB` | `108.5 MiB` / `130.0 MiB` |
| Metaspace | `-XX:MaxMetaspaceSize=256m` | `140–180 MiB` | `256.0 MiB` |
| JIT Code Cache | `-XX:ReservedCodeCacheSize=128m` | `60–90 MiB` | `128.0 MiB` |
| Direct / Off-Heap (Netty/gRPC/Kafka) | `-XX:MaxDirectMemorySize=128m` | `40–80 MiB` | `128.0 MiB` |
| G1GC Native + JVM/musl Overhead | `-XX:+UseG1GC` | `100–130 MiB` | `150.0 MiB` |
| **Total Container RSS** | **Limit `2048 MiB` (`1536 MiB` request)** | **~1,075–1,564 MiB** | **`1,999.3 MiB` / `2,020.8 MiB` (< `2048 MiB`)** |

Aligned `JAVA_OPTS` across `Dockerfile`, `docker-compose.yml`, and `k8s/04-deployment.yml`:
```text
-XX:+UseContainerSupport -XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=60.0 -Xss512k -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=128m -XX:MaxDirectMemorySize=128m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=<writable_path>/heapdump.hprof -Djava.security.egd=file:/dev/./urandom
```
(`HeapDumpPath=/app/logs/heapdump.hprof` in Docker's shared `epricing-logs` volume; `/tmp/heapdump.hprof` in Kubernetes where `tmp-dir` `emptyDir.sizeLimit` is `1536Mi`, isolating a `<= 1228.8 MiB` heap dump from the `512Mi` `log-dir` volume.)

---

## 5. R4 — Portable Production Deployment Hardening

All deployment hardening changes are 100% cloud-agnostic with zero vendor-specific (GCP-only) integrations or dependencies:

1. **Build Context & Image (`Dockerfile`, `.dockerignore`)**:
   - Added `.dockerignore` excluding `.git/`, `target/`, `logs/`, `docs/`, `k8s/`, `grafana/`, `prometheus/`, `showcase/`, `webhook-logger/`, `*.md`, `*.xlsx`, `*.csv`, and `.github/` (reducing Docker build context to `~523.5 kB` uncompressed / `110.25 kB` BuildKit compressed transfer).
   - Pinned non-root user UID/GID `10001` (`addgroup -g 10001 -S epricing && adduser -u 10001 -S epricing -G epricing`) to match Kubernetes `securityContext.runAsUser: 10001`.
   - Removed `-Dspring.profiles.active=docker` from `JAVA_OPTS` and set `ENV SPRING_PROFILES_ACTIVE=docker` so environments like Kubernetes (`SPRING_PROFILES_ACTIVE=k8s`) override the profile cleanly.
   - Updated `HEALTHCHECK` target to `http://127.0.0.1:8081/actuator/health/liveness` to avoid Alpine IPv6 `::1` `localhost` resolution issues.
   - Updated `TemporalConfig.@PreDestroy stopWorkers()` to call `workerFactory.awaitTermination(15, TimeUnit.SECONDS)` after `shutdown()` so in-flight Temporal tasks drain gracefully before container exit.

2. **Docker Compose (`docker-compose.yml`)**:
   - Sized `epricing-service` at `reservations.memory: 1536M`, `limits.memory: 2048M`, `cpus: '2.0'`, aligned `JAVA_OPTS`, and added `stop_grace_period: 35s`.
   - Added `temporal` `healthcheck` (`temporal operator cluster health --address $$(hostname):7233 || exit 1`) and switched `epricing-service` and `temporal-ui` `depends_on.temporal.condition` to `service_healthy`.
   - Switched `epricing-service` `healthcheck` to `/actuator/health/readiness` so `traffic-generator` (`condition: service_healthy`) waits until database connectivity and readiness are up.
   - Parameterized dev credentials (`${DB_USERNAME:-yugabyte}`, `${DB_PASSWORD:-yugabyte}`, `${GF_SECURITY_ADMIN_PASSWORD:-Bank@grafana123}`, `${TEMPORAL_DB_USER:-temporal}`, `${TEMPORAL_DB_PASSWORD:-temporal}`).

3. **Spring Boot, Logback & OpenTelemetry (`application.yml`, `logback-spring.xml`, `OpenTelemetryConfig.java`)**:
   - Added `server.shutdown: graceful`, `spring.lifecycle.timeout-per-shutdown-phase: 25s`, bounded `server.tomcat.threads` (`max: 50`, `min-spare: 8`, `accept-count: 100`), `management.endpoint.health.probes.enabled: true`, and `livenessState` / `readinessState` health groups.
   - Added `k8s` to `logback-spring.xml` `<springProfile name="prod,docker,staging,k8s">` (and `&amp; !k8s` in the fallback negation) so Kubernetes pods run at `INFO` with `org.hibernate.SQL=OFF` instead of hitting the `DEBUG` fallback, and made `maxFileSize`, `maxHistory`, and `totalSizeCap` Spring-configurable (`epricing.logging.*` via `LOG_MAX_FILE_SIZE`, `LOG_MAX_HISTORY`, `LOG_TOTAL_SIZE_CAP`).
   - Updated `OpenTelemetryConfig.java` to read `otel.traces.sampler` (`OTEL_TRACES_SAMPLER`, default `parentbased_always_on`) and optional `otel.traces.sampler.arg` (`OTEL_TRACES_SAMPLER_ARG`) rather than hardcoding the sampler.

4. **Kubernetes Manifests (`k8s/00`–`08`)**:
   - `k8s/00-namespace.yml`: Raised `ResourceQuota` `epricing-quota` to `requests.cpu: "10"`, `requests.memory: 20Gi`, `limits.cpu: "24"`, `limits.memory: 24Gi` so HPA scaling up to `maxReplicas: 10` (`10 * 2000m = 20` CPU, `15Gi` mem requested, `20Gi` mem limited) **plus** a `RollingUpdate` (`maxSurge: 1`, `maxUnavailable: 0`) surge pod (`11 * 2000m = 22 CPU <= 24 CPU`, `11 * 2048Mi = 22Gi <= 24Gi`) never hits a quota deadlock at peak scale.
   - `k8s/01-rbac.yml` & `k8s/04-deployment.yml`: Disabled unused ServiceAccount token mounting (`automountServiceAccountToken: false`).
   - `k8s/02-configmap.yml`: Added `LOGGING_FILE_PATH: "/var/log/epricing"` plus `LOG_MAX_FILE_SIZE: "50MB"`, `LOG_MAX_HISTORY: "5"`, `LOG_TOTAL_SIZE_CAP: "256MB"` (safely bounded within `log-dir` `512Mi` `sizeLimit`), fixed `EPRICING_BASE_RATE` -> `EPRICING_PRICING_BASE_RATE`, added graceful shutdown vars, and removed dead keys (`TEMPORAL_SERVICE_ADDRESS`, unused `EPRICING_LARGE_LOAN_*`).
   - `k8s/04-deployment.yml`: Updated resources (`500m`/`1536Mi` requests, `2000m`/`2048Mi` limits), aligned `JAVA_OPTS` with `HeapDumpPath=/tmp/heapdump.hprof`, added `terminationGracePeriodSeconds: 45` + `lifecycle.preStop` (`sleep 5`), expanded `startupProbe` (`initialDelaySeconds: 15`, `periodSeconds: 5`, `failureThreshold: 36` = 195s window), added `topologySpreadConstraints` + `podAntiAffinity`, and sized `emptyDir.sizeLimit` cleanly (`tmp-dir: 1536Mi` for `<= 1228.8 MiB` max heap dump; `log-dir: 512Mi` >= `256MB` `LOG_TOTAL_SIZE_CAP` + active `50MB` log file).
   - `k8s/06-hpa.yml`: Removed `memory: 80%` metric (which would false-trigger at `1229Mi` on `1536Mi` requests for a JVM with `InitialRAMPercentage=50.0`) and kept `cpu: 70%` scaling.
   - `k8s/08-networkpolicy.yml`: Added standard `networking.k8s.io/v1` `NetworkPolicy` allowing ingress on `8080`/`8081` and egress for DNS (`53`), YugabyteDB (`5433`), Kafka (`9092`), Temporal (`7233`), and OTel (`4318`).

---

## 6. Verification Commands & Observed Results

All verification checks were executed after the final source, container, and Kubernetes edits:

### 6.1 Full Maven Build & Test Suite
Command:
```powershell
mvn -B clean verify --no-transfer-progress
```
Observed Result (`Exit Code: 0`, `01:21 min` with live Docker Compose stack running):
- `ApplicationContextBootstrapTest`: `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0` (`@SpringBootTest(RANDOM_PORT)` + H2 PostgreSQL mode + `DatabaseMigrationInitializer` V1–V8 + R1 terminal-state JPA proxy tests + R1 concurrent stale-version `saveAndFlush` optimistic-lock race test + R3 live REST validation)
- `KafkaContextBootstrapTest`: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`
- `DatabaseMigrationInitializerTest`: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` (including HikariCP/YugabyteDB `0A000` advisory-lock connection eviction recovery)
- `PricingActivitiesImplTest`: `Tests run: 18, Failures: 0, Errors: 0, Skipped: 0`
- `PricingRequestDtoValidationTest`: `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0`
- `PricingControllerTest`: `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`
- `TemporalPricingControllerTest`: `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`
- `LoanPricingWorkflowTest`: `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0` (including `WorkflowReplayer` regression test against captured Nov 19 pre-versioned history and concurrent early-signal approval test)
- `PricingServiceTest` (7), `PricingPersistenceAtomicityTest` (3), `PricingEventProducerTest` (11), `PricingCalculatorTest` (20), `HealthControllerTest` (5), `PagedResponseTest` (3), `LoanPricingEventTest` (3), `StructuredLoggerTest` (4), `TemporalPricingServiceTest` (1) — all `Failures: 0, Errors: 0, Skipped: 0`.
- Live-broker integration tests (`TemporalWorkflowLiveIntegrationTest` 1, `KafkaLiveBrokerIntegrationTest` 1): executed and passed against live Kafka (`:9092`) and Temporal (`:7233`) (`Tests run: 131, Failures: 0, Errors: 0, Skipped: 0` across `19` test classes, **`BUILD SUCCESS`**); guarded by JUnit `Assumptions` for offline runs.

### 6.2 Docker Compose & Image Build Verification
1. `docker compose config --quiet; Write-Output "COMPOSE_EXIT:$LASTEXITCODE"` -> `COMPOSE_EXIT:0`.
2. `docker build -t epricing-service:hardened-verify .` -> `BUILD_EXIT:0` (build context `110.25kB` via `.dockerignore`, multi-stage Maven package + Alpine JRE runtime).
3. `docker run --rm --entrypoint sh epricing-service:hardened-verify -c "id"` -> `uid=10001(epricing) gid=10001(epricing) groups=10001(epricing)` (`ID_EXIT:0`).
4. `docker image inspect epricing-service:hardened-verify --format '{{json .Config.Env}} | {{json .Config.Healthcheck.Test}} | User={{.Config.User}}'` -> confirmed `SPRING_PROFILES_ACTIVE=docker`, all R2 `JAVA_OPTS` flags, `User=epricing`, and `wget --quiet --tries=1 --spider http://127.0.0.1:8081/actuator/health/liveness || exit 1`.

### 6.3 Kubernetes Manifest & Cross-File Consistency Validation (Static, No Live Cluster)
Validated all 9 `k8s/*.yml` files (17 YAML documents) alongside `logback-spring.xml` and `application.yml` via PyYAML + cross-file consistency assertions:
- Observed Output (`Exit Code: 0`):
  `K8S_AND_RESOURCE_VALIDATION_PASS: files=9, docs=17, peak_pods=11 (maxReplicas=10+maxSurge=1, maxUnavailable=0), surge_cpu=22.0/24.0, surge_mem_Mi=22528/24576, log_cap_Mi=291.8/512, heap_Mi=1228.8/1536, threads_realistic=217 (rss=1999.3Mi), threads_ceiling=260 (rss=2020.8Mi), gcp_bloat=0`
- Confirmed `ResourceQuota` covers `maxReplicas: 10` + `maxSurge: 1` (`11` pods: `22.0 <= 24.0` CPU limits, `22528 Mi <= 24576 Mi` memory limits), `LOGGING_FILE_PATH` matches `/var/log/epricing` `emptyDir`, `LOG_TOTAL_SIZE_CAP + LOG_MAX_FILE_SIZE` (`291.8 MiB`) fits inside `log-dir` `512Mi`, `HeapDumpPath=/tmp/heapdump.hprof` (`<= 1228.8 MiB`) fits inside `tmp-dir` `1536Mi`, `automountServiceAccountToken: false` is enforced, `NetworkPolicy` covers ingress `8080`/`8081` + egress `53`/`5433`/`9092`/`7233`/`4318`, and zero cloud-vendor-specific (GCP-only) integrations exist in `Dockerfile`, `pom.xml`, `application.yml`, or `k8s/*.yml`.
