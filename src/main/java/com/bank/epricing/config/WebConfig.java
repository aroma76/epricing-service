package com.bank.epricing.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  WebConfig.java — MVC and CORS Configuration                            ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║  WHY THIS CLASS EXISTS:                                                  ║
 * ║  CORS: Browser security requires explicit permission for cross-origin   ║
 * ║  requests. Without CORS config, frontends or Grafana (running on :3000) ║
 * ║  cannot make API calls to our service (:8080).                          ║
 * ║                                                                          ║
 * ║  NOTE ON ASYNC / CONCURRENCY:                                            ║
 * ║  Background orchestration, durable retries, and asynchronous workflows   ║
 * ║  are now managed by Temporal (see com.bank.epricing.temporal),           ║
 * ║  eliminating the need for custom Spring @Async thread pool boilerplate.  ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    @Value("${epricing.cors.allowed-origins:}")
    private String corsAllowedOrigins;

    /**
     * CORS Configuration.
     *
     * CORS (Cross-Origin Resource Sharing) is a browser security feature.
     * When JavaScript on grafana.Bank.com tries to call api.Bank.com,
     * the browser blocks the request unless our server says:
     * "Yes, I allow requests from grafana.Bank.com"
     *
     * COMPLIANCE: The allowed-origins property has NO default. If CORS_ALLOWED_ORIGINS
     * is not set in the environment, CORS is denied for all cross-origin requests.
     * This is fail-closed (secure by default).
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (corsAllowedOrigins == null || corsAllowedOrigins.isBlank()) {
            // No origins configured — deny all cross-origin requests (secure default).
            log.warn("CORS_ALLOWED_ORIGINS is not set — all cross-origin requests will be blocked.");
            return;
        }
        String[] origins = corsAllowedOrigins.split(",");
        log.info("CORS configured | allowedOrigins={}", corsAllowedOrigins);
        registry.addMapping("/**")
            .allowedOriginPatterns(origins)
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders(
                "Content-Type",
                "Authorization",
                "X-API-Key",
                "Idempotency-Key",
                "X-Request-ID",
                "X-Customer-ID",
                "X-Correlation-ID"
            )
            .exposedHeaders(
                "X-Request-ID",  // Expose our request ID to the client
                "X-Trace-ID"     // Expose trace ID for client-side correlation
            )
            .allowCredentials(false)  // true only if using session cookies
            .maxAge(3600);  // Browser caches CORS preflight for 1 hour
    }
}
