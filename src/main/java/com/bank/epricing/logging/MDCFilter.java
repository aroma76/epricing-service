package com.bank.epricing.logging;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  MDCFilter.java — Mapped Diagnostic Context Servlet Filter              ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║  WHAT IS MDC?                                                            ║
 * ║  MDC (Mapped Diagnostic Context) is a thread-local key-value map        ║
 * ║  provided by SLF4J. Everything you put in MDC is automatically         ║
 * ║  included in EVERY log statement made on that thread.                   ║
 * ║                                                                          ║
 * ║  WHY MDC MATTERS:                                                        ║
 * ║  Without MDC, logs look like:                                           ║
 * ║    INFO  - Pricing requested                                             ║
 * ║    INFO  - Rate calculated: 8.75%                                       ║
 * ║    INFO  - Saved to database                                             ║
 * ║                                                                          ║
 * ║  With MDC, every log line automatically includes the request context:   ║
 * ║    INFO  - Pricing requested | traceId=abc123 requestId=REQ-001         ║
 * ║    INFO  - Rate calculated: 8.75% | traceId=abc123 requestId=REQ-001   ║
 * ║    INFO  - Saved to database | traceId=abc123 requestId=REQ-001         ║
 * ║                                                                          ║
 * ║  Now in Grafana Loki, you can search:                                   ║
 * ║    {application="epricing-service"} | traceId = "abc123"               ║
 * ║  And see ALL log lines for that one request, even if 1000 other         ║
 * ║  requests were being processed concurrently.                            ║
 * ║                                                                          ║
 * ║  THIS IS THE CORRELATION BRIDGE between metrics, logs, and traces.      ║
 * ║                                                                          ║
 * ║  HOW IT WORKS:                                                           ║
 * ║  This is a Servlet Filter — it runs for EVERY HTTP request:            ║
 * ║    1. Request comes in                                                   ║
 * ║    2. MDCFilter runs FIRST (Order 1 — highest priority)                ║
 * ║    3. Adds requestId, customerId, etc. to MDC                          ║
 * ║    4. Micrometer Tracing adds traceId and spanId to MDC automatically  ║
 * ║    5. Request processed — all logs include the MDC context             ║
 * ║    6. CRITICAL: MDC.clear() is called in finally block                  ║
 * ║       Without clear(), MDC leaks to the next request on thread reuse.  ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */
@Component
@Order(1)  // Run before all other filters — MDC must be set up first
public class MDCFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(MDCFilter.class);

    // MDC Key constants — used by logback-spring.xml <includeMdcKeyName>
    // Having constants prevents typos: MDC.put("tarceid",...) would silently fail
    public static final String REQUEST_ID = "requestId";
    public static final String CUSTOMER_ID = "customerId";
    public static final String PRODUCT_CODE = "productCode";
    public static final String OPERATION_TYPE = "operationType";
    public static final String CLIENT_IP = "clientIp";
    public static final String HTTP_METHOD = "httpMethod";
    public static final String REQUEST_URI = "requestUri";

    @Override
    public void doFilter(
        ServletRequest servletRequest,
        ServletResponse servletResponse,
        FilterChain filterChain
    ) throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        try {
            // ─── STEP 1: Generate or extract request ID ───────────────────
            // Check if client sent a request ID (for request tracing in distributed systems).
            // If not, generate one. This allows end-to-end correlation:
            // Client → API Gateway → epricing-service → downstream-service
            // All using the same requestId.
            String requestId = request.getHeader("X-Request-ID");
            if (requestId == null || requestId.isBlank()) {
                // Generate a short, unique ID. UUID is too long for headers;
                // we use the first 16 chars for readability.
                requestId = "REQ-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            }

            // ─── STEP 2: Populate MDC ─────────────────────────────────────
            // These values are now automatically included in EVERY log statement
            // made on this thread from this point forward.
            MDC.put(REQUEST_ID, requestId);
            MDC.put(CLIENT_IP, getClientIpAddress(request));
            MDC.put(HTTP_METHOD, request.getMethod());
            MDC.put(REQUEST_URI, request.getRequestURI());

            // Extract customer ID from header if provided (for logging enrichment)
            // In a real system with JWT auth, you'd extract this from the JWT token.
            String customerId = request.getHeader("X-Customer-ID");
            if (customerId != null && !customerId.isBlank()) {
                MDC.put(CUSTOMER_ID, customerId);
            }

            // ─── STEP 3: Add request ID to response header ────────────────
            // The client receives their requestId in the response.
            // They can use it in support tickets: "My request ID is REQ-ABC123"
            response.setHeader("X-Request-ID", requestId);

            // ─── STEP 4: Log the incoming request (structured) ────────────
            // This log automatically includes all MDC fields (traceId, requestId, etc.)
            log.info("Incoming request | method={} | uri={} | clientIp={}",
                request.getMethod(),
                request.getRequestURI(),
                getClientIpAddress(request)
            );

            // ─── STEP 5: Pass to next filter / controller ─────────────────
            long startTime = System.currentTimeMillis();
            filterChain.doFilter(servletRequest, servletResponse);
            long duration = System.currentTimeMillis() - startTime;

            // ─── STEP 6: Log the outgoing response ────────────────────────
            log.info("Request completed | method={} | uri={} | status={} | durationMs={}",
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus(),
                duration
            );

        } finally {
            // ─── CRITICAL: ALWAYS clear MDC in finally block ──────────────
            // WHY: Tomcat uses a thread pool. When a request finishes, its thread
            // is returned to the pool and reused for the NEXT request.
            // If you don't clear MDC, the next request gets this request's
            // customerId, requestId, etc. This is a serious data leak and
            // makes debugging impossible (logs show wrong customer data).
            //
            // The finally block runs even if an exception is thrown,
            // ensuring MDC is always cleaned up.
            MDC.clear();
        }
    }

    /**
     * Extracts the real client IP address, accounting for reverse proxies.
     *
     * WHY THIS IS COMPLEX:
     * In production, requests go through:
     *   Client → CDN → Load Balancer → API Gateway → Your App
     *
     * By the time the request reaches your app, request.getRemoteAddr()
     * returns the load balancer's IP, not the client's IP.
     *
     * The actual client IP is passed via headers:
     *   X-Forwarded-For: 203.0.113.195, 70.41.3.18 (left-most is original client)
     *   X-Real-IP: 203.0.113.195
     *
     * SECURITY NOTE: Never trust X-Forwarded-For blindly — clients can spoof it.
     * Only trust it if it comes from a known trusted proxy.
     */
    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty() && !"unknown".equalsIgnoreCase(xForwardedFor)) {
            // X-Forwarded-For can be a comma-separated list: "client, proxy1, proxy2"
            // The leftmost value is the original client IP
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        return request.getRemoteAddr();
    }

    @Override
    public void init(FilterConfig filterConfig) {
        log.info("MDCFilter initialized — structured logging context enabled");
    }

    @Override
    public void destroy() {
        log.info("MDCFilter destroyed");
    }
}
