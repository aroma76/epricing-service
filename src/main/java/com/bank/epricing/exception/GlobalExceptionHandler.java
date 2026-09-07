package com.bank.epricing.exception;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.bank.epricing.logging.StructuredLogger;

/**
 * GlobalExceptionHandler — Centralized Error Handling and Observability Hub.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MeterRegistry meterRegistry;
    private final StructuredLogger structuredLogger;

    public GlobalExceptionHandler(MeterRegistry meterRegistry, StructuredLogger structuredLogger) {
        this.meterRegistry = meterRegistry;
        this.structuredLogger = structuredLogger;
    }

    @ExceptionHandler(PricingException.class)
    public ResponseEntity<ErrorResponse> handlePricingException(
        PricingException ex,
        WebRequest request
    ) {
        meterRegistry.counter(
            "pricing.errors.total",
            "error_type", ex.getErrorCode(),
            "http_status", String.valueOf(ex.getHttpStatus().value())
        ).increment();

        Span currentSpan = Span.current();
        currentSpan.setStatus(
            io.opentelemetry.api.trace.StatusCode.ERROR,
            ex.getMessage()
        );
        currentSpan.recordException(ex);

        log.warn(
            "Business exception handled | errorCode={} | status={} | message={} | path={}",
            ex.getErrorCode(),
            ex.getHttpStatus().value(),
            ex.getMessage(),
            request.getDescription(false)
        );

        ErrorResponse errorResponse = ErrorResponse.builder()
            .timestamp(LocalDateTime.now())
            .status(ex.getHttpStatus().value())
            .error(ex.getHttpStatus().getReasonPhrase())
            .errorCode(ex.getErrorCode())
            .message(ex.getMessage())
            .path(request.getDescription(false).replace("uri=", ""))
            .traceId(currentSpan.getSpanContext().getTraceId())
            .build();

        return ResponseEntity.status(ex.getHttpStatus()).body(errorResponse);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
        MethodArgumentNotValidException ex,
        WebRequest request
    ) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            fieldErrors.put(fieldName, errorMessage);
        });

        meterRegistry.counter(
            "pricing.errors.total",
            "error_type", "VALIDATION_FAILED",
            "http_status", "400"
        ).increment();

        structuredLogger.logValidationFailure(request.getDescription(false), fieldErrors);

        log.warn("Validation failed | fields={} | path={}",
            fieldErrors,
            request.getDescription(false)
        );

        Span.current().setStatus(
            io.opentelemetry.api.trace.StatusCode.ERROR,
            "Validation failed: " + fieldErrors
        );

        ErrorResponse errorResponse = ErrorResponse.builder()
            .timestamp(LocalDateTime.now())
            .status(HttpStatus.BAD_REQUEST.value())
            .error("Validation Failed")
            .errorCode("PRICING_VALIDATION_FAILED")
            .message("Request validation failed. Check 'fieldErrors' for details.")
            .path(request.getDescription(false).replace("uri=", ""))
            .fieldErrors(fieldErrors)
            .traceId(Span.current().getSpanContext().getTraceId())
            .build();

        return ResponseEntity.badRequest().body(errorResponse);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
        Exception ex,
        WebRequest request
    ) {
        String errorRefId = "ERR-" + UUID.randomUUID().toString().substring(0, 13).toUpperCase();

        log.error(
            "Unhandled exception | errorRef={} | exception={} | path={} | message={}",
            errorRefId,
            ex.getClass().getSimpleName(),
            request.getDescription(false),
            ex.getMessage(),
            ex
        );

        meterRegistry.counter(
            "pricing.errors.total",
            "error_type", "UNHANDLED_" + ex.getClass().getSimpleName(),
            "http_status", "500"
        ).increment();

        Span currentSpan = Span.current();
        currentSpan.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, "Internal server error");
        currentSpan.recordException(ex);

        ErrorResponse errorResponse = ErrorResponse.builder()
            .timestamp(LocalDateTime.now())
            .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
            .error("Internal Server Error")
            .errorCode("INTERNAL_SERVER_ERROR")
            .message("An unexpected error occurred. Please contact support with reference: " + errorRefId)
            .path(request.getDescription(false).replace("uri=", ""))
            .traceId(currentSpan.getSpanContext().getTraceId())
            .build();

        return ResponseEntity.internalServerError().body(errorResponse);
    }

    public static class ErrorResponse {

        @JsonProperty("timestamp")
        private LocalDateTime timestamp;

        @JsonProperty("status")
        private int status;

        @JsonProperty("error")
        private String error;

        @JsonProperty("error_code")
        private String errorCode;

        @JsonProperty("message")
        private String message;

        @JsonProperty("path")
        private String path;

        @JsonProperty("field_errors")
        private Map<String, String> fieldErrors;

        @JsonProperty("trace_id")
        private String traceId;

        public ErrorResponse() {
        }

        public ErrorResponse(LocalDateTime timestamp, int status, String error, String errorCode, String message,
                             String path, Map<String, String> fieldErrors, String traceId) {
            this.timestamp = timestamp;
            this.status = status;
            this.error = error;
            this.errorCode = errorCode;
            this.message = message;
            this.path = path;
            this.fieldErrors = fieldErrors;
            this.traceId = traceId;
        }

        public LocalDateTime getTimestamp() { return timestamp; }
        public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

        public int getStatus() { return status; }
        public void setStatus(int status) { this.status = status; }

        public String getError() { return error; }
        public void setError(String error) { this.error = error; }

        public String getErrorCode() { return errorCode; }
        public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }

        public Map<String, String> getFieldErrors() { return fieldErrors; }
        public void setFieldErrors(Map<String, String> fieldErrors) { this.fieldErrors = fieldErrors; }

        public String getTraceId() { return traceId; }
        public void setTraceId(String traceId) { this.traceId = traceId; }

        public static ErrorResponseBuilder builder() {
            return new ErrorResponseBuilder();
        }

        public static class ErrorResponseBuilder {
            private LocalDateTime timestamp;
            private int status;
            private String error;
            private String errorCode;
            private String message;
            private String path;
            private Map<String, String> fieldErrors;
            private String traceId;

            ErrorResponseBuilder() {}

            public ErrorResponseBuilder timestamp(LocalDateTime timestamp) { this.timestamp = timestamp; return this; }
            public ErrorResponseBuilder status(int status) { this.status = status; return this; }
            public ErrorResponseBuilder error(String error) { this.error = error; return this; }
            public ErrorResponseBuilder errorCode(String errorCode) { this.errorCode = errorCode; return this; }
            public ErrorResponseBuilder message(String message) { this.message = message; return this; }
            public ErrorResponseBuilder path(String path) { this.path = path; return this; }
            public ErrorResponseBuilder fieldErrors(Map<String, String> fieldErrors) { this.fieldErrors = fieldErrors; return this; }
            public ErrorResponseBuilder traceId(String traceId) { this.traceId = traceId; return this; }

            public ErrorResponse build() {
                return new ErrorResponse(timestamp, status, error, errorCode, message, path, fieldErrors, traceId);
            }
        }
    }
}
