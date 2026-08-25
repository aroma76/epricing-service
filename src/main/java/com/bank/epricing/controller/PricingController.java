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
 * REST controller for ePricing endpoints.
 * Intentionally thin — delegates all business logic to PricingService.
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
     * GET /pricing?customerId=CUST001234
     * Returns pricing history for a customer. If customerId is omitted, returns last 10 records.
     * Supports optional pagination via ?page=0&size=20 when customerId is provided.
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
     * POST /pricing
     * Submits a new pricing calculation. Returns 201 with the calculated rate, EMI, and traceId.
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
     * GET /pricing/{id}
     * Returns a specific pricing record by its database ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<PricingResponseDto> getPricingById(@PathVariable Long id) {
        log.info("GET /pricing/{}", id);
        PricingResponseDto response = pricingService.getPricingById(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Extracts the real client IP, accounting for reverse proxies.
     * X-Forwarded-For contains the original client IP when traffic passes through a load balancer.
     */
    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank() && !"unknown".equalsIgnoreCase(xForwardedFor)) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp;
        }
        return request.getRemoteAddr();
    }

    /** Masks customer ID for log output. e.g. "CUST001234" → "CUST****1234" */
    private String maskCustomerId(String customerId) {
        if (customerId == null || customerId.length() <= 4) return "****";
        return customerId.substring(0, 4) + "****" + customerId.substring(customerId.length() - 4);
    }
}
