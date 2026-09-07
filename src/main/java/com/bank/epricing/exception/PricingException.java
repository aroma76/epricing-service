package com.bank.epricing.exception;

import org.springframework.http.HttpStatus;

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  PricingException.java — Domain-Specific Exception Hierarchy            ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║  WHY CUSTOM EXCEPTIONS:                                                  ║
 * ║  Using Java's generic RuntimeException everywhere means:                 ║
 * ║    - You cannot distinguish business errors from technical errors       ║
 * ║    - You cannot map errors to correct HTTP status codes                 ║
 * ║    - Error messages are inconsistent                                     ║
 * ║    - Metrics cannot track error TYPES separately                        ║
 * ║                                                                          ║
 * ║  With custom exceptions:                                                 ║
 * ║    PricingException → 422 Unprocessable Entity (business rule failure)  ║
 * ║    ResourceNotFoundException → 404 Not Found                            ║
 * ║    InsufficientCreditScoreException → 400 Bad Request                   ║
 * ║                                                                          ║
 * ║  OBSERVABILITY VALUE:                                                    ║
 * ║  The GlobalExceptionHandler catches these and:                          ║
 * ║    1. Increments specific Micrometer error counters                     ║
 * ║       e.g., pricing.errors.total{type=INSUFFICIENT_CREDIT_SCORE}        ║
 * ║    2. Logs structured JSON with error type, errorCode, customerId       ║
 * ║    3. Records exception type as span attribute in the OTel trace        ║
 * ║  This allows Grafana to show: "Error Rate by Error Type" dashboard.    ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */
public class PricingException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final String errorCode;

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    /**
     * Base constructor for all pricing exceptions.
     * 
     * @param message    Human-readable message (logged and returned to client)
     * @param errorCode  Machine-readable code (for client error handling)
     * @param httpStatus HTTP status to return (set by subclass)
     */
    public PricingException(String message, String errorCode, HttpStatus httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    /**
     * Constructor that chains to another exception (wraps a cause).
     * Use when catching a technical exception and re-throwing as a business exception.
     * The original exception is preserved as the 'cause' for stack trace debugging.
     */
    public PricingException(String message, String errorCode, HttpStatus httpStatus, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INNER EXCEPTION CLASSES — Specific business error types
    // ═══════════════════════════════════════════════════════════════════════
    // WHY INNER CLASSES: Keeps all pricing exceptions in one file.
    // They are logically grouped. You import PricingException.InsufficientCreditScore
    // which is self-documenting.

    /**
     * Thrown when a customer's credit score is too low for the requested product.
     * HTTP 422: The request was well-formed but couldn't be processed due to business rules.
     */
    public static class InsufficientCreditScoreException extends PricingException {
        public InsufficientCreditScoreException(String customerId, int creditScore, int minimumRequired) {
            super(
                String.format(
                    "Customer %s has credit score %d which is below the minimum required score of %d",
                    customerId, creditScore, minimumRequired
                ),
                "PRICING_INSUFFICIENT_CREDIT_SCORE",
                HttpStatus.UNPROCESSABLE_ENTITY
            );
        }
    }

    /**
     * Thrown when the requested loan amount exceeds the customer's eligibility.
     */
    public static class LoanAmountExceedsEligibilityException extends PricingException {
        public LoanAmountExceedsEligibilityException(String customerId, double requestedAmount, double eligibleAmount) {
            super(
                String.format(
                    "Customer %s is eligible for a maximum loan of ₹%.2f but requested ₹%.2f",
                    customerId, eligibleAmount, requestedAmount
                ),
                "PRICING_AMOUNT_EXCEEDS_ELIGIBILITY",
                HttpStatus.UNPROCESSABLE_ENTITY
            );
        }
    }

    /**
     * Thrown when a pricing request is not found in the database.
     * HTTP 404: Resource does not exist.
     */
    public static class PricingRequestNotFoundException extends PricingException {
        public PricingRequestNotFoundException(Long requestId) {
            super(
                String.format("Pricing request with ID %d not found", requestId),
                "PRICING_REQUEST_NOT_FOUND",
                HttpStatus.NOT_FOUND
            );
        }

        public PricingRequestNotFoundException(String traceId) {
            super(
                String.format("Pricing request with trace ID %s not found", traceId),
                "PRICING_REQUEST_NOT_FOUND_BY_TRACE",
                HttpStatus.NOT_FOUND
            );
        }
    }

    /**
     * Thrown when the pricing calculation fails due to invalid business parameters.
     * HTTP 400: The client sent bad data.
     */
    public static class PricingCalculationException extends PricingException {
        public PricingCalculationException(String reason) {
            super(
                "Pricing calculation failed: " + reason,
                "PRICING_CALCULATION_FAILED",
                HttpStatus.BAD_REQUEST
            );
        }

        public PricingCalculationException(String reason, Throwable cause) {
            super(
                "Pricing calculation failed: " + reason,
                "PRICING_CALCULATION_FAILED",
                HttpStatus.INTERNAL_SERVER_ERROR,
                cause
            );
        }
    }

    /**
     * Thrown when a product type is not supported by the pricing engine.
     */
    public static class UnsupportedProductTypeException extends PricingException {
        public UnsupportedProductTypeException(String productType) {
            super(
                String.format("Product type '%s' is not supported by the pricing engine", productType),
                "PRICING_UNSUPPORTED_PRODUCT",
                HttpStatus.BAD_REQUEST
            );
        }
    }

    /**
     * Thrown when the pricing service is temporarily unavailable.
     * HTTP 503: Service Unavailable — triggers circuit breaker / retry in clients.
     */
    public static class PricingServiceUnavailableException extends PricingException {
        public PricingServiceUnavailableException(String reason) {
            super(
                "Pricing service temporarily unavailable: " + reason,
                "PRICING_SERVICE_UNAVAILABLE",
                HttpStatus.SERVICE_UNAVAILABLE
            );
        }
    }
}
