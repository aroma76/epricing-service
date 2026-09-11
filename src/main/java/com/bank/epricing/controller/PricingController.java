package com.bank.epricing.controller;

import com.bank.epricing.dto.PricingRequestDto;
import com.bank.epricing.dto.PricingResponseDto;
import com.bank.epricing.service.PricingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  PricingController.java — REST API Endpoints                            ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║  WHY THIS CLASS EXISTS:                                                  ║
 * ║  The Controller is the entry point for all HTTP requests.              ║
 * ║  It is intentionally THIN — it only:                                   ║
 * ║    1. Receives HTTP requests                                            ║
 * ║    2. Validates input (via @Valid)                                      ║
 * ║    3. Delegates to PricingService for business logic                   ║
 * ║    4. Returns HTTP responses                                             ║
 * ║                                                                          ║
 * ║  NO BUSINESS LOGIC IN CONTROLLERS.                                      ║
 * ║  No database calls. No calculations. No metrics recording.             ║
 * ║  Everything lives in the Service layer.                                 ║
 * ║                                                                          ║
 * ║  OBSERVABILITY:                                                          ║
 * ║  Spring Boot + OTel automatically instruments every method in          ║
 * ║  @RestController classes. Each HTTP request gets:                       ║
 * ║    - A parent span in Grafana Tempo                                     ║
 * ║    - http.server.requests metrics in Prometheus                         ║
 * ║    - MDC context enriched by MDCFilter for logs                        ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */
@RestController
@RequestMapping("/pricing")
public class PricingController {

    private static final Logger log = LoggerFactory.getLogger(PricingController.class);

    private final PricingService pricingService;

    public PricingController(PricingService pricingService) {
        this.pricingService = pricingService;
    }

    /**
     * GET /api/v1/pricing
     *
     * Returns a list of all pricing requests for a given customer.
     *
     * @RequestParam: Reads query parameter from URL.
     *   Example: GET /api/v1/pricing?customerId=CUST001234
     *
     * @RequestParam(required=false): Parameter is optional.
     *   If not provided, returns recent pricing (capped at 10 for demo).
     *
     * ResponseEntity<List<PricingResponseDto>>:
     *   ResponseEntity gives full control over HTTP response:
     *     - Status code
     *     - Headers
     *     - Body
     *   Without ResponseEntity: Spring returns 200 automatically.
     *   With ResponseEntity: You control every aspect of the response.
     */
    @GetMapping
    public ResponseEntity<?> getPricingHistory(
        @RequestParam(required = false) String customerId,
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size
    ) {
        log.info("GET /pricing | customerId={} | page={} | size={}", customerId, page, size);
        if (page != null && customerId != null && !customerId.isBlank()) {
            int pageSize = (size != null && size > 0 && size <= 100) ? size : 20;
            Page<PricingResponseDto> pagedResult = pricingService.getPricingHistory(
                customerId, PageRequest.of(page, pageSize)
            );
            return ResponseEntity.ok(pagedResult);
        }
        List<PricingResponseDto> history = pricingService.getPricingHistory(customerId);
        log.info("Pricing history retrieved | customerId={} | count={}", customerId, history.size());
        return ResponseEntity.ok(history);
    }

    /**
     * POST /api/v1/pricing
     *
     * Submits a new pricing calculation request.
     *
     * @Valid: Triggers Bean Validation on the request body.
     *   If validation fails (e.g., null customerId, amount out of range),
     *   Spring throws MethodArgumentNotValidException BEFORE this method runs.
     *   GlobalExceptionHandler catches it and returns a 400 with field errors.
     *
     * @RequestBody: Deserializes JSON request body to PricingRequestDto.
     *   Jackson reads the JSON and maps fields. If JSON is malformed,
     *   Spring throws HttpMessageNotReadableException → 400 Bad Request.
     *
     * HttpServletRequest httpRequest: Used to extract the client's IP address
     *   for the audit log (who made this request?).
     *
     * HTTP 201 CREATED: The correct status for a resource creation operation.
     *   HTTP 200 OK: Generic success (should not be used for creation).
     *   HTTP 201 CREATED: "A resource was created." Semantically correct for POST.
     */
    @PostMapping
    public ResponseEntity<PricingResponseDto> calculatePricing(
        @Valid @RequestBody PricingRequestDto requestDto,
        HttpServletRequest httpRequest
    ) {
        log.info("POST /pricing | customerId={} | productType={}",
            maskCustomerId(requestDto.getCustomerId()),
            requestDto.getProductType()
        );

        String clientIp = extractClientIp(httpRequest);
        PricingResponseDto response = pricingService.calculatePricing(requestDto, clientIp);

        log.info("Pricing calculation complete | customerId={} | rate={}% | traceId={}",
            maskCustomerId(requestDto.getCustomerId()),
            response.getInterestRatePA(),
            response.getTraceId()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /api/v1/pricing/{id}
     *
     * Retrieves a specific pricing calculation by ID.
     *
     * @PathVariable: Extracts the {id} segment from the URL.
     *   Example: GET /api/v1/pricing/42 → id = 42
     *
     * If the ID doesn't exist, PricingService throws PricingRequestNotFoundException
     * → GlobalExceptionHandler returns 404.
     */
    @GetMapping("/{id}")
    public ResponseEntity<PricingResponseDto> getPricingById(@PathVariable Long id) {
        log.info("GET /pricing/{}", id);
        PricingResponseDto response = pricingService.getPricingById(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Extracts the real client IP address, accounting for reverse proxies and load balancers.
     *
     * In production, requests arrive through:
     *   Client → CDN → Load Balancer → API Gateway → This App
     *
     * getRemoteAddr() returns the PROXY IP, not the client IP.
     * The actual client IP is carried in X-Forwarded-For (first value in the chain).
     *
     * SECURITY NOTE: X-Forwarded-For can be spoofed by clients.
     * Only trust it when your infrastructure sets it from a known trusted proxy.
     */
    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank() && !"unknown".equalsIgnoreCase(xForwardedFor)) {
            // X-Forwarded-For: "client, proxy1, proxy2" — leftmost is the original client IP
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp;
        }
        return request.getRemoteAddr();
    }

    /**
     * Masks a customer ID for log output. Example: "CUST001234" → "CUST****1234".
     */
    private String maskCustomerId(String customerId) {
        if (customerId == null || customerId.length() <= 4) return "****";
        return customerId.substring(0, 4) + "****" + customerId.substring(customerId.length() - 4);
    }
}
