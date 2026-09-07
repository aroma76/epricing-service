# Test Strategy

**Test Framework:** JUnit 5 (Jupiter) + Mockito + Spring Test (MockMvc)
**Test Location:** `src/test/java/com/bank/epricing/`
**Command:** `mvn test`

---

## Overview

The ePricing service follows a **pragmatic testing strategy** that emphasizes:

1. **Unit tests** for pure business logic (`PricingCalculator`)
2. **Slice tests** for the service layer (`PricingService`) with mocked repositories
3. **Controller tests** using `MockMvc` in standalone mode (no Spring context)

No integration tests or Spring Boot test (`@SpringBootTest`) are used. This keeps tests fast and CI-friendly.

---

## Test Classes

### PricingCalculatorTest

**File:** [`PricingCalculatorTest.java`](../../src/test/java/com/bank/epricing/util/PricingCalculatorTest.java)
**Type:** Pure Unit Tests (no mocking)

`PricingCalculator` is side-effect-free — no mocking needed. Tests call real methods and assert return values.

| Test Method | Coverage |
|---|---|
| `testCalculateInterestRate_HomeLoan_ExcellentCredit` | Credit score ≥ 800 → −1.00% discount applied |
| `testCalculateInterestRate_LargeLoanDiscount` | Loan ≥ ₹50L → −0.25% additional discount |
| `testCalculateEmi` | Standard EMI formula verification (₹10L, 8.50%, 120mo → ₹12,398.57) |
| `testDetermineRiskCategory` | All 5 risk buckets + null handling |
| `testValidateEligibility_LowCreditScore` | Score 620 throws `InsufficientCreditScoreException` |
| `testValidateEligibility_Valid` | Score 750 passes without exception |

**Example:**
```java
@Test
@DisplayName("Should calculate EMI accurately")
void testCalculateEmi() {
    BigDecimal emi = pricingCalculator.calculateEmi(
        new BigDecimal("1000000"), new BigDecimal("8.50"), 120
    );
    assertEquals(new BigDecimal("12398.57"), emi);
}
```

---

### PricingServiceTest

**File:** [`PricingServiceTest.java`](../../src/test/java/com/bank/epricing/service/PricingServiceTest.java)
**Type:** Unit Tests with Mockito mocking

Dependencies mocked:
- `PricingRepository` (Mockito `mock()`)
- `PricingAuditRepository` (Mockito `mock()`)
- `Tracer`, `Span`, `SpanBuilder`, `SpanContext` (Mockito `mock()`)

Real implementations used:
- `PricingCalculator` (no side effects — real object)
- `PricingMetrics` with `SimpleMeterRegistry` (real Micrometer, no I/O)
- `StructuredLogger` (real object — only logs)
- `PricingAuditService` (real, thin wrapper)

| Test Method | What's tested |
|---|---|
| `testCalculatePricing_Success` | Full service flow — saves twice, returns response |
| `testGetPricingHistory_WithCustomerId` | Customer filter delegates to `findByCustomerId` |
| `testGetPricingHistory_BlankCustomerId` | Blank customer ID uses `findTop10ByOrderByCreatedAtDesc` |
| `testGetPricingById_Success` | Finds by ID and maps to DTO |

**OTel Mock Setup:**
```java
SpanContext spanContext = mock(SpanContext.class);
when(spanContext.getTraceId()).thenReturn("mock-trace-id-12345");
when(span.getSpanContext()).thenReturn(spanContext);
when(spanBuilder.startSpan()).thenReturn(span);
when(tracer.spanBuilder(anyString())).thenReturn(spanBuilder);
```

The OTel mocks ensure `PricingService.calculatePricing()` can call `tracer.spanBuilder()` and `span.setStatus()` without `NullPointerException` during tests.

**Repository save verification:**
```java
verify(pricingRepository, times(2)).save(any(PricingRequest.class));
```

`PricingService` saves the entity twice: once as `PENDING` (pre-calculation) and once as `CALCULATED` (post-calculation). The test verifies this exact behaviour.

---

### PricingControllerTest

**File:** [`PricingControllerTest.java`](../../src/test/java/com/bank/epricing/controller/PricingControllerTest.java)
**Type:** MockMvc standalone controller tests

Uses `MockMvcBuilders.standaloneSetup()` — no full Spring context. This is faster than `@SpringBootTest` and tests only the controller layer.

```java
mockMvc = MockMvcBuilders.standaloneSetup(pricingController)
    .setControllerAdvice(exceptionHandler)
    .build();
```

`GlobalExceptionHandler` is registered with MockMvc to test error responses.

| Test Method | Verifies |
|---|---|
| `testCalculatePricing_ValidRequest` | `201 Created` + response fields |
| `testCalculatePricing_InvalidCustomerId` | `400 Bad Request` + `PRICING_VALIDATION_FAILED` |
| `testGetPricingHistory` | `200 OK` + customer history array |
| `testGetPricingById` | `200 OK` + single record by ID |
| `testGetPricingById_NotFound` | `404 Not Found` + `PRICING_REQUEST_NOT_FOUND` error code |
| `testCalculatePricing_LowCreditScore` | `422 Unprocessable Entity` + `PRICING_INSUFFICIENT_CREDIT_SCORE` |
| `testCalculatePricing_UnsupportedProductType` | `400 Bad Request` + `PRICING_VALIDATION_FAILED` |

**Example controller test:**
```java
@Test
void testCalculatePricing_ValidRequest() throws Exception {
    PricingRequestDto dto = PricingRequestDto.builder()
        .customerId("CUST001234")
        .productType("HOME_LOAN")
        .loanAmount(new BigDecimal("5000000.00"))
        .loanTenureMonths(240)
        .creditScore(780)
        .annualIncome(new BigDecimal("2400000.00"))
        .build();

    when(pricingRepository.save(any(PricingRequest.class))).thenAnswer(invocation -> {
        PricingRequest req = invocation.getArgument(0);
        if (req.getId() == null) req.setId(101L);
        return req;
    });

    mockMvc.perform(post("/pricing")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(dto)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.request_id").value(101))
        .andExpect(jsonPath("$.interest_rate_pa").value(8.00));
}
```

---

## Test Execution

```bash
# Run all 17 tests (PricingCalculatorTest: 6, PricingServiceTest: 4, PricingControllerTest: 7)
mvn test

# Run specific test class
mvn test -Dtest=PricingCalculatorTest

# Run with verbose output
mvn test -Dtest=PricingServiceTest -Dsurefire.failIfNoSpecifiedTests=false

# Generate coverage report (requires JaCoCo plugin)
mvn verify
```

---

## Coverage Gaps and Future Work

> **Note:** Key controller edge cases (404 Not Found, 422 Low Credit Score, 400 Validation) are fully covered in the 17-test suite. The following scenarios are recommended future additions:

| Recommended Coverage | Priority |
|---|---|
| `PricingCalculator` — edge case: zero interest rate EMI | Medium |
| `PricingCalculator` — loan amount exactly at ₹50L boundary (large loan discount) | Medium |
| `PricingService` — technical exception path (500 path) | High |
| `PricingController` — GET with no `customerId` (returns top 10) | Medium |
| `PricingAuditService` — async execution verification | Low |
| Integration test — full Spring context with H2 | Low |

---

## Cross-References

- [PricingCalculator.md](../utilities/PricingCalculator.md) — tested logic
- [PricingService.md](../services/PricingService.md) — tested service
- [PricingController.md](../controllers/PricingController.md) — tested controller
- [ExceptionHandling.md](../exception-handling/ExceptionHandling.md) — exception paths tested
