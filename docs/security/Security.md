# Security

> **Status: Not Implemented**

Authentication and authorization are **not in scope** for this version of the ePricing service. All API endpoints are publicly accessible.

---

## Current State

The service has no:
- JWT or OAuth2 token validation
- Role-based access control (RBAC)
- API rate limiting (configuration exists in `application.yml` but is not wired to code)
- Input sanitization beyond Bean Validation
- Mutual TLS (mTLS)

The actuator port (`:8081`) is separate from the API port (`:8080`) but is not network-restricted.

---

## Production Security Requirements

### 1. Authentication (JWT / OAuth2)

Add Spring Security with OAuth2 Resource Server:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
```

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://auth.bank.com/realms/banking
```

### 2. Role-Based Access Control

```java
@PreAuthorize("hasRole('PRICING_USER')")
@PostMapping
public PricingResponseDto calculatePricing(...) { ... }

@PreAuthorize("hasRole('PRICING_ADMIN')")
@GetMapping("/admin/all")
public List<PricingResponseDto> getAllPricing() { ... }
```

### 3. Rate Limiting (Resilience4j)

The `epricing.rate-limit` configuration is already defined but not implemented:

```yaml
epricing:
  rate-limit:
    requests-per-second: 100
    burst-capacity: 200
```

Implementation would use Resilience4j `@RateLimiter` or a Spring Cloud Gateway rate limiter.

### 4. Actuator Security

Restrict actuator endpoints to management network:

```java
@Bean
public SecurityFilterChain actuatorSecurity(HttpSecurity http) throws Exception {
    http.requestMatcher(EndpointRequest.toAnyEndpoint())
        .authorizeRequests()
        .anyRequest().hasRole("ACTUATOR_USER");
    return http.build();
}
```

### 5. Secret Management

Replace hardcoded credentials with:
- **Kubernetes Secrets** + External Secrets Operator
- **HashiCorp Vault** with Spring Vault
- **AWS Secrets Manager** with Spring Cloud AWS

### 6. TLS

Terminate TLS at the load balancer or API gateway. For service-to-service calls, enable mTLS via service mesh (Istio/Linkerd).

---

## Security-Related Existing Controls

Some security controls ARE in place:

| Control | Implementation |
|---|---|
| Stack trace suppression | `server.error.include-stacktrace: never` |
| Non-root container user | `USER epricing` in Dockerfile |
| Read-only config mounts | `:ro` on all config volume mounts in Docker Compose |
| Grafana anonymous access disabled | `GF_AUTH_ANONYMOUS_ENABLED: "false"` |
| No sensitive fields in API response | `PricingResponseDto` excludes internal entity fields |

---

## Cross-References

- [ApplicationConfig.md](../configuration/ApplicationConfig.md) — rate limiting config (not implemented)
- [WebConfig.md](../configuration/WebConfig.md) — CORS configuration
- [DockerCompose.md](../docker/DockerCompose.md) — Grafana security settings
