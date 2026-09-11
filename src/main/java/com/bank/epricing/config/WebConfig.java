package com.bank.epricing.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.Executor;

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  WebConfig.java — MVC, CORS, and Async Thread Pool Configuration        ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║  WHY THIS CLASS EXISTS:                                                  ║
 * ║  1. CORS: Browser security requires explicit permission for             ║
 * ║     cross-origin requests. Without CORS config, Grafana (running on    ║
 * ║     :3000) cannot make API calls to our service (:8080).               ║
 * ║                                                                          ║
 * ║  2. ASYNC THREAD POOL: The @Async annotation in PricingAuditService    ║
 * ║     needs a thread pool to execute on. Without this bean, Spring uses  ║
 * ║     a default single-threaded executor (terrible for production).       ║
 * ║                                                                          ║
 * ║  @EnableAsync: Activates Spring's asynchronous execution capability.   ║
 * ║  Without this annotation, @Async methods run SYNCHRONOUSLY             ║
 * ║  (silently — no error, just slow).                                     ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */
@Configuration
@EnableAsync
public class WebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    @Value("${epricing.async.core-pool-size:5}")
    private int asyncCorePoolSize;

    @Value("${epricing.async.max-pool-size:20}")
    private int asyncMaxPoolSize;

    @Value("${epricing.async.queue-capacity:100}")
    private int asyncQueueCapacity;

    @Value("${epricing.async.thread-name-prefix:epricing-async-}")
    private String asyncThreadNamePrefix;

    @Value("${epricing.cors.allowed-origins:*}")
    private String corsAllowedOrigins;

    /**
     * CORS Configuration.
     *
     * CORS (Cross-Origin Resource Sharing) is a browser security feature.
     * When JavaScript on grafana.Bank.com tries to call api.Bank.com,
     * the browser blocks the request unless our server says:
     * "Yes, I allow requests from grafana.Bank.com"
     *
     * This is done via HTTP response headers:
     *   Access-Control-Allow-Origin: http://localhost:3000
     *   Access-Control-Allow-Methods: GET, POST, PUT, DELETE
     *   Access-Control-Allow-Headers: Content-Type, X-Request-ID
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
        registry.addMapping("/api/**")
            // Set via CORS_ALLOWED_ORIGINS env var:
            // e.g., CORS_ALLOWED_ORIGINS=https://grafana.bank.com,https://portal.bank.com
            .allowedOriginPatterns(origins)
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders(
                "Content-Type",
                "Authorization",
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

    /**
     * Custom Async Thread Pool Executor.
     *
     * WHY CUSTOM (not default):
     * Spring's default async executor is SimpleAsyncTaskExecutor which:
     *   - Creates a NEW thread for EVERY async call
     *   - Has NO thread limit → under load, creates thousands of threads
     *   - Can crash the JVM with OutOfMemoryError
     *
     * ThreadPoolTaskExecutor has:
     *   - corePoolSize: Threads always running and waiting for work
     *   - maxPoolSize: Max threads under heavy load
     *   - queueCapacity: How many tasks wait in queue before spawning new threads
     *   - threadNamePrefix: Named threads appear in thread dumps (makes debugging easy)
     *
     * Thread pool sizing:
     *   - For I/O-bound work (DB writes): 2 × CPU cores is a good starting point
     *   - For CPU-bound work: CPU cores (no benefit from more threads than cores)
     *   - Our audit log writing is I/O-bound → use more threads than CPU cores
     *
     * Bean name "epricingAsyncExecutor" matches the @Async("epricingAsyncExecutor")
     * annotation in PricingAuditService. This ensures audit tasks use OUR pool,
     * not some other pool.
     */
    @Bean(name = "epricingAsyncExecutor")
    public Executor epricingAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(asyncCorePoolSize);
        executor.setMaxPoolSize(asyncMaxPoolSize);
        executor.setQueueCapacity(asyncQueueCapacity);
        executor.setThreadNamePrefix(asyncThreadNamePrefix);
        // Gracefully wait for running tasks to finish before shutdown
        executor.setWaitForTasksToCompleteOnShutdown(true);
        // Wait up to 30s for in-progress tasks on application shutdown
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("Async executor initialized | corePoolSize={} | maxPoolSize={} | queueCapacity={}",
            asyncCorePoolSize, asyncMaxPoolSize, asyncQueueCapacity);
        return executor;
    }
}
