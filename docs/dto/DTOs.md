# Data Transfer Objects (DTOs)

**Package:** `com.bank.epricing.dto`
**Files:**
- [`PricingRequestDto.java`](../../src/main/java/com/bank/epricing/dto/PricingRequestDto.java)
- [`PricingResponseDto.java`](../../src/main/java/com/bank/epricing/dto/PricingResponseDto.java)

---

## Purpose

DTOs (Data Transfer Objects) form the **API contract**. They decouple the HTTP API surface from internal JPA entities. The controller receives a `PricingRequestDto`, passes it to the service, and the service returns a `PricingResponseDto`. The `PricingRequest` entity is never exposed directly to the client.

**Why separate DTOs from entities?**
- Entity changes (adding fields, renaming columns) don't break the API contract.
- API field names can follow conventions independent of DB column names (snake_case JSON vs camelCase Java).
- Validation annotations stay on the DTO — entities are not cluttered with HTTP concerns.
- Sensitive entity fields (e.g., internal IDs, processing metadata) can be omitted from responses.

---

## PricingRequestDto

**Represents the JSON body of `POST /api/v1/pricing`.**

### Class Declaration

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class PricingRequestDto { ... }
```

`@JsonNaming(SnakeCaseStrategy.class)` instructs Jackson to map JSON `snake_case` field names to Java `camelCase` fields automatically. No `@JsonProperty` annotation needed on each field.

### Fields and Validation

| Field (Java) | JSON key | Type | Constraints |
|---|---|---|---|
| `customerId` | `customer_id` | `String` | `@NotBlank`, `@Pattern("CUST[0-9]{6,10}")` |
| `productType` | `product_type` | `String` | `@NotBlank` |
| `loanAmount` | `loan_amount` | `BigDecimal` | `@NotNull`, `@Positive`, `@DecimalMax("100000000")` |
| `loanTenureMonths` | `loan_tenure_months` | `Integer` | `@NotNull`, `@Min(12)`, `@Max(360)` |
| `creditScore` | `credit_score` | `Integer` | `@Min(300)`, `@Max(900)` |
| `annualIncome` | `annual_income` | `BigDecimal` | `@NotNull`, `@DecimalMin("100000.00")` |

### Validation Details

- **`customerId`**: Must match `CUST` followed by 6–10 digits. Example: `CUST001234`.
- **`loanAmount`**: Maximum ₹10 crore (100,000,000). Minimum: any positive amount.
- **`loanTenureMonths`**: 12 months (1 year) to 360 months (30 years).
- **`creditScore`**: 300 (lowest) to 900 (highest) — CIBIL score range.
- **`annualIncome`**: **Required.** Minimum ₹1,00,000 per year. This field is mandatory for the FOIR (Fixed Obligation to Income Ratio) eligibility check. If a borrower's total monthly obligations exceed 50% of monthly income, the request is rejected. Without income data this check cannot run — which is a regulatory compliance gap.

### Example Request

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

---

## PricingResponseDto

**Returned by all endpoints that produce pricing data.**

### Class Declaration

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class PricingResponseDto { ... }
```

### Fields

| Field (Java) | JSON key | Type | Description |
|---|---|---|---|
| `requestId` | `request_id` | `Long` | DB primary key of the pricing record |
| `customerId` | `customer_id` | `String` | Customer identifier |
| `productType` | `product_type` | `String` | Loan product type |
| `loanAmount` | `loan_amount` | `BigDecimal` | Requested loan amount |
| `loanTenureMonths` | `loan_tenure_months` | `Integer` | Loan tenure in months |
| `interestRatePA` | `interest_rate_pa` | `BigDecimal` | Calculated annual interest rate (%) |
| `emiAmount` | `emi_amount` | `BigDecimal` | Monthly EMI amount |
| `totalPayableAmount` | `total_payable_amount` | `BigDecimal` | Total repayment (principal + interest) |
| `totalInterestPayable` | `total_interest_payable` | `BigDecimal` | Total interest component |
| `riskCategory` | `risk_category` | `String` | Risk classification |
| `status` | `status` | `String` | Pricing lifecycle status |
| `traceId` | `trace_id` | `String` | OTel trace ID for cross-referencing |
| `processedAt` | `processed_at` | `LocalDateTime` | Timestamp of processing |

### Example Response

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

---

## JSON Serialization Configuration

Jackson global config in `application.yml`:

```yaml
spring:
  jackson:
    serialization:
      write-dates-as-timestamps: false   # ISO 8601 format
    time-zone: Asia/Kolkata              # IST (+05:30)
```

This ensures `processedAt` appears as `"2024-01-15T10:30:00.000+05:30"` rather than an epoch number.

---

## Cross-References

- [PricingController.md](../controllers/PricingController.md) — how DTOs are used in endpoints
- [PricingService.md](../services/PricingService.md) — DTO to entity mapping
- [PricingRequest.md](../entities/PricingRequest.md) — corresponding entity
- [ExceptionHandling.md](../exception-handling/ExceptionHandling.md) — validation error responses
- [PricingAPI.md](../api/PricingAPI.md) — full API reference
