package com.bank.epricing.logging;

import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  StructuredLogger.java — Business-Aware Structured Log Events           ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║  WHY THIS CLASS EXISTS:                                                  ║
 * ║  Raw log statements like:                                               ║
 * ║    log.info("Processing pricing for " + customerId);                    ║
 * ║  are hard to parse, filter, and alert on in Grafana/Loki.              ║
 * ║                                                                          ║
 * ║  This class provides TYPED, STRUCTURED log methods that:                ║
 * ║    1. Use MDC to add context automatically                              ║
 * ║    2. Include the traceId from the current OTel span                   ║
 * ║    3. Use consistent field names across all log events                  ║
 * ║    4. Are easy to query in Grafana: {event_type="PRICING_STARTED"}     ║
 * ║                                                                          ║
 * ║  PRODUCTION VALUE:                                                       ║
 * ║  At 3 AM during an incident, you search Grafana Loki:                  ║
 * ║    {application="epricing-service"} | json | event_type="PRICING_ERROR" ║
 * ║  and immediately see all pricing errors with customer context.          ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */
@Component
public class StructuredLogger {

    private static final Logger log = LoggerFactory.getLogger(StructuredLogger.class);

    /**
     * Logs when a pricing request starts processing.
     * Sets MDC fields that will appear in all subsequent log lines
     * within this request's processing chain.
     */
    public void logPricingStarted(String customerId, String productType, BigDecimal amount) {
        // Add business context to MDC so ALL subsequent logs in this thread
        // automatically include customerId and productCode
        MDC.put(MDCFilter.CUSTOMER_ID, customerId);
        MDC.put(MDCFilter.PRODUCT_CODE, productType);
        MDC.put(MDCFilter.OPERATION_TYPE, "PRICING_CALCULATION");

        // structured log: every field is a separate named parameter
        // logstash-logback-encoder converts this to JSON with each {} placeholder as a field
        log.info("Pricing calculation started | event_type={} | customerId={} | productType={} | amount={}",
            "PRICING_STARTED", customerId, productType, amount
        );
    }

    /**
     * Logs a successful pricing calculation with full result details.
     * This event is queryable in Grafana: "Show me all HOME_LOAN approvals today"
     */
    public void logPricingCompleted(
        String customerId,
        String productType,
        BigDecimal rate,
        BigDecimal emi,
        long processingTimeMs
    ) {
        // Include the OTel trace ID explicitly for manual cross-referencing
        String traceId = Span.current().getSpanContext().getTraceId();

        log.info(
            "Pricing calculation completed | event_type={} | customerId={} | " +
            "productType={} | rate={}% | emi={} | durationMs={} | traceId={}",
            "PRICING_COMPLETED", customerId, productType, rate, emi, processingTimeMs, traceId
        );
    }

    /**
     * Logs a business-rule rejection (low credit score, exceeds eligibility, etc.)
     * WARN level: Not a technical error, but a business event worth tracking.
     * Grafana alert: "If rejection rate > 20% → alert risk team"
     */
    public void logPricingRejected(String customerId, String productType, String reason, String errorCode) {
        log.warn(
            "Pricing request rejected | event_type={} | customerId={} | productType={} | " +
            "reason={} | errorCode={}",
            "PRICING_REJECTED", customerId, productType, reason, errorCode
        );
    }

    /**
     * Logs a technical/system error — always at ERROR level.
     * ERROR logs:
     *   1. Trigger PagerDuty/OpsGenie alerts in production
     *   2. Increment error rate metrics in Grafana
     *   3. Create RED spans in Grafana Tempo
     *
     * The throwable parameter causes logback to include the full stack trace
     * in the JSON log output.
     */
    public void logPricingError(String customerId, String productType, String errorMessage, Throwable cause) {
        log.error(
            "Pricing calculation failed | event_type={} | customerId={} | productType={} | error={}",
            "PRICING_ERROR", customerId, productType, errorMessage,
            cause  // Logback appends full stack trace in JSON log
        );
    }

    /**
     * Logs audit events — for regulatory compliance and security monitoring.
     * These go to a separate audit logger in production (different log file,
     * longer retention, more secure access controls).
     */
    public void logAuditEvent(String action, String performedBy, String target, String outcome) {
        log.info(
            "AUDIT | event_type={} | action={} | performedBy={} | target={} | outcome={}",
            "AUDIT_EVENT", action, performedBy, target, outcome
        );
    }

    /**
     * Logs a slow operation warning.
     * Threshold: > 500ms is considered slow for a pricing API.
     * This feeds into the "Slow Request" Grafana panel.
     */
    public void logSlowOperation(String operation, long durationMs, String context) {
        log.warn(
            "Slow operation detected | event_type={} | operation={} | durationMs={} | context={} | " +
            "threshold_ms=500",
            "SLOW_OPERATION", operation, durationMs, context
        );
    }

    /**
     * Logs request validation failures (e.g. invalid customer ID, loan amount out of bounds).
     */
    public void logValidationFailure(String path, Object fieldErrors) {
        log.warn(
            "Validation failure | event_type={} | path={} | fieldErrors={}",
            "VALIDATION_FAILURE", path, fieldErrors
        );
    }

    /**
     * Logs database errors explicitly.
     */
    public void logDatabaseError(String operation, String entity, String errorMessage, Throwable cause) {
        log.error(
            "Database operation error | event_type={} | dbOperation={} | entity={} | error={}",
            "DATABASE_ERROR", operation, entity, errorMessage, cause
        );
    }

    /**
     * Logs database operation metrics.
     * Useful for correlating database performance with application metrics.
     */
    public void logDatabaseOperation(String operation, String entity, long durationMs, boolean success) {
        if (durationMs > 200) {
            // Slow DB query — worth logging at WARN
            log.warn(
                "Slow database operation | event_type={} | dbOperation={} | entity={} | " +
                "durationMs={} | success={}",
                "SLOW_DB_OPERATION", operation, entity, durationMs, success
            );
        } else {
            log.debug(
                "Database operation | event_type={} | dbOperation={} | entity={} | " +
                "durationMs={} | success={}",
                "DB_OPERATION", operation, entity, durationMs, success
            );
        }
    }
}
