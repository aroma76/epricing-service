# PricingCalculator

**Package:** `com.bank.epricing.util`
**File:** [`PricingCalculator.java`](../../src/main/java/com/bank/epricing/util/PricingCalculator.java)
**Stereotype:** `@Component`

---

## Purpose

`PricingCalculator` is the **pure mathematical core** of the ePricing system. It contains all business arithmetic: interest rate determination, EMI calculation, eligibility validation, and risk classification.

**Design principle:** This class is **side-effect-free**. It has no dependencies on repositories, external services, or any state. Every method takes inputs and returns outputs deterministically. This makes it:
- Independently testable without mocking
- Reusable across different service layers
- Easy to reason about — no hidden state changes

---

## Business Logic

### Interest Rate Formula

The final interest rate is computed in stages:

```
1. Base Rate = 8.50% (configurable)
2. Product Multiplier:
   - HOME_LOAN:      × 1.0   → 8.50%
   - AUTO_LOAN:      × 1.2   → 10.20%
   - BUSINESS_LOAN:  × 1.5   → 12.75%
   - PERSONAL_LOAN:  × 1.8   → 15.30%
   - EDUCATION_LOAN: × 1.1   → 9.35%

3. Credit Score Adjustment (additive, applied to productRate):
   - Score ≥ 800: −1.00%  (premium customer discount)
   - Score ≥ 750: −0.50%
   - Score ≥ 700: no adjustment
   - Score ≥ 650: +0.50%  (elevated risk premium)
   - Score < 650: REJECT  (never reaches this step — validateEligibility throws first)

4. Large Loan Discount:
   - Amount ≥ ₹50,00,000: −0.25%

5. Floor: Rate cannot go below 6.50% (configurable minimum)
```

**Example — HOME_LOAN, credit score 780, ₹60 lakh:**

```
8.50 × 1.0 = 8.50  (base × product multiplier)
8.50 − 0.50 = 8.00  (credit score ≥ 750 → −0.50%)
8.00 − 0.25 = 7.75  (large loan ≥ ₹50L → −0.25%)
Final rate = 7.75%
```

---

## Methods

### `calculateInterestRate(String productType, Integer creditScore, BigDecimal loanAmount) → BigDecimal`

Implements the staged interest rate calculation described above.

```java
BigDecimal rate = pricingCalculator.calculateInterestRate(
    "HOME_LOAN", 780, new BigDecimal("6000000")
);
// Returns: 7.75
```

Returns a `BigDecimal` with scale 2 (e.g., `8.50`, `13.25`).

---

### `calculateEmi(BigDecimal principal, BigDecimal annualRatePercent, int tenureMonths) → BigDecimal`

Implements the standard **reducing-balance EMI formula**:

```
EMI = P × r × (1 + r)^n  /  ((1 + r)^n − 1)

Where:
  P = principal (loan amount)
  r = monthly interest rate = annualRate / 12 / 100
  n = tenure in months
```

**Implementation detail:** Uses `BigDecimal` arithmetic with `MathContext.DECIMAL64` for financial precision. Intermediate calculations use 10 decimal places; final result is rounded to 2 decimal places using `HALF_UP` rounding.

```java
BigDecimal emi = pricingCalculator.calculateEmi(
    new BigDecimal("1000000"), new BigDecimal("8.50"), 120
);
// Returns: 12398.57
```

**Edge case — zero interest rate:** Returns `principal / tenureMonths` (simple division, no compound interest formula needed).

---

### `validateEligibility(String customerId, Integer creditScore, BigDecimal loanAmount, BigDecimal annualIncome) → void`

Enforces **business rule gates** before pricing. Throws specific exception subtypes:

| Rule | Check | Exception thrown |
|---|---|---|
| Minimum credit score | `creditScore < 650` | `InsufficientCreditScoreException` |
| Maximum loan-to-income | `loanAmount > annualIncome × 0.60` | `LoanAmountExceedsEligibilityException` |

```java
// Throws InsufficientCreditScoreException (HTTP 422)
pricingCalculator.validateEligibility("CUST001", 620, new BigDecimal("500000"), new BigDecimal("1200000"));

// Passes silently
pricingCalculator.validateEligibility("CUST001", 750, new BigDecimal("500000"), new BigDecimal("1200000"));
```

**Null handling:** If `creditScore` is null, it defaults to a `MEDIUM` risk category but is NOT rejected at this validation stage — null credit score is treated as an unknown (medium) risk.

---

### `determineRiskCategory(Integer creditScore) → String`

Maps a credit score to a risk bucket string:

| Credit Score | Risk Category |
|---|---|
| ≥ 800 | `LOW` |
| 750 – 799 | `LOW_MEDIUM` |
| 700 – 749 | `MEDIUM` |
| 650 – 699 | `MEDIUM_HIGH` |
| < 650 | `HIGH` |
| `null` | `MEDIUM` (default) |

---

## Precision and Rounding

All monetary calculations use `BigDecimal` (never `double` or `float`). Rounding:

- **Interest rates**: Scale 2, `HALF_UP` rounding (e.g., `8.505` → `8.51`)
- **EMI amounts**: Scale 2, `HALF_UP` rounding
- **Total payable**: `emiAmount × tenureMonths`, scale 2

`HALF_UP` is the standard rounding mode in banking (e.g., ₹0.005 rounds to ₹0.01, not ₹0.00).

---

## Test Coverage

All methods are covered by `PricingCalculatorTest`:

```java
// Rate calculation
testCalculateInterestRate_HomeLoan_ExcellentCredit()     // Score 820 → 7.50%
testCalculateInterestRate_LargeLoanDiscount()            // Score 760, ₹60L → 7.75%

// EMI calculation
testCalculateEmi()                                       // ₹10L, 8.50%, 120mo → ₹12398.57

// Risk category
testDetermineRiskCategory()                              // All 5 categories + null

// Eligibility validation
testValidateEligibility_LowCreditScore()                 // 620 → throws
testValidateEligibility_Valid()                          // 750 → passes
```

---

## Cross-References

- [PricingService.md](../services/PricingService.md) — calls this calculator
- [ExceptionHandling.md](../exception-handling/ExceptionHandling.md) — exceptions thrown here
- [PricingRequest.md](../entities/PricingRequest.md) — fields populated by this calculator
- [TestStrategy.md](../testing/TestStrategy.md) — `PricingCalculatorTest` details
- [features/PricingEngine.md](../features/PricingEngine.md) — complete pricing feature
