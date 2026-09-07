# PricingController

**Package:** `com.bank.epricing.controller`
**File:** [`PricingController.java`](../../src/main/java/com/bank/epricing/controller/PricingController.java)
**Stereotype:** `@RestController`, `@RequestMapping("/pricing")`

---

## Purpose

`PricingController` is the **thin HTTP adapter** layer. Its sole responsibilities are:

1. Map incoming HTTP requests to service method calls.
2. Validate the request body using Jakarta Bean Validation (`@Valid`).
3. Return appropriate HTTP status codes and response bodies.
4. Delegate all business logic to `PricingService`.

The controller contains **no business logic**. Any decision about rates, eligibility, or persistence belongs in `PricingService`.

---

## Base URL

The controller is mounted at `/pricing`. Combined with the server context path (`/api/v1`), all endpoints are reachable at:

```
http://localhost:8080/api/v1/pricing
```

---

## Endpoint Reference

### POST `/pricing` — Calculate Loan Pricing

Submits a new loan pricing request. Validates the DTO and delegates to `PricingService.calculatePricing()`.

**HTTP Method:** `POST`
**Request Content-Type:** `application/json`
**Success Response:** `201 Created`

**Request Body (`PricingRequestDto`):**

```json
{
  "customer_id": "CUST001234",
  "product_type": "HOME_LOAN",
  "loan_amount": 5000000.00,
  "loan_tenure_months": 240,
  "credit_score": 780,
  "annual_income": 2400000.00
}
```

**Success Response Body (`PricingResponseDto`):**

```json
{
  "request_id": 101,
  "customer_id": "CUST001234",
  "product_type": "HOME_LOAN",
  "loan_amount": 5000000.00,
  "loan_tenure_months": 240,
  "interest_rate_pa": 8.00,
  "emi_amount": 41822.00,
  "total_payable_amount": 10037280.00,
  "total_interest_payable": 5037280.00,
  "risk_category": "LOW_MEDIUM",
  "status": "CALCULATED",
  "trace_id": "abc123def456",
  "processed_at": "2024-01-15T10:30:00.000+05:30"
}
```

**Error Responses:**

| Status | Error Code | Cause |
|---|---|---|
| `400 Bad Request` | `PRICING_VALIDATION_FAILED` | Validation failure (missing fields, invalid format, unsupported product type) |
| `422 Unprocessable Entity` | `PRICING_INSUFFICIENT_CREDIT_SCORE` | Business rule violation (credit score < 650, high debt-to-income ratio) |
| `500 Internal Server Error` | `PRICING_CALCULATION_FAILED` | Unexpected technical or calculation error |

---

### GET `/pricing` — Get Pricing History

Returns pricing history for a customer, or the 10 most-recent records if no `customerId` is provided.

**HTTP Method:** `GET`
**Query Parameter:** `customerId` (optional)
**Success Response:** `200 OK` — array of `PricingResponseDto`

**Example Request:**
```
GET /api/v1/pricing?customerId=CUST001234
```

**Example Response:**
```json
[
  {
    "request_id": 1,
    "customer_id": "CUST001234",
    "product_type": "HOME_LOAN",
    "interest_rate_pa": 8.50,
    "emi_amount": 43391.16,
    "status": "CALCULATED",
    "trace_id": "abc123def456"
  }
]
```

---

### GET `/pricing/{id}` — Get Pricing by ID

Retrieves a single pricing record by its database ID.

**HTTP Method:** `GET`
**Path Variable:** `id` (Long)
**Success Response:** `200 OK` — single `PricingResponseDto`

**Error Responses:**

| Status | Error Code | Cause |
|---|---|---|
| `404 Not Found` | `PRICING_REQUEST_NOT_FOUND` | No pricing record exists with the given ID |

---

## Implementation Notes

### Thin Controller Pattern

```java
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
public PricingResponseDto calculatePricing(
        @Valid @RequestBody PricingRequestDto requestDto,
        HttpServletRequest request) {
    return pricingService.calculatePricing(requestDto, request.getRemoteAddr());
}
```

- `@Valid` triggers Jakarta Bean Validation before the method body executes.
- `@ResponseStatus(HttpStatus.CREATED)` automatically sets `201 Created` on success.
- The controller extracts the client IP from `HttpServletRequest` and passes it to the service for audit logging.

### Client IP Extraction

The raw `request.getRemoteAddr()` returns the connecting socket IP. In production with a load balancer, `MDCFilter.getClientIpAddress()` provides the real client IP via `X-Forwarded-For` header. The controller passes the raw socket address to `PricingService`, which uses it for audit record population.

---

## Validation Constraints

Validation is defined in [`PricingRequestDto`](../dto/DTOs.md):

| Field | Constraint |
|---|---|
| `customerId` | `@NotBlank`, `@Pattern(regexp = "CUST[0-9]{6,10}")` |
| `productType` | `@NotBlank` |
| `loanAmount` | `@NotNull`, `@Positive`, `@DecimalMax("100000000")` |
| `loanTenureMonths` | `@NotNull`, `@Min(12)`, `@Max(360)` |
| `creditScore` | `@Min(300)`, `@Max(900)` |
| `annualIncome` | `@NotNull`, `@Positive` |

Validation failures produce a `400 Bad Request` response handled by `GlobalExceptionHandler`.

---

## Cross-References

- [PricingService.md](../services/PricingService.md) — business logic called by this controller
- [DTOs.md](../dto/DTOs.md) — request and response DTO definitions
- [ExceptionHandling.md](../exception-handling/ExceptionHandling.md) — how errors are formatted
- [PricingAPI.md](../api/PricingAPI.md) — complete API reference
